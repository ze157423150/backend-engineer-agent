"""Bounded, isolated checkpoint retries for the existing long-context evaluator.
Every attempt runs in a fresh JVM; only accepted boundaries feed subsequent turns.
Failures and their measured cost remain in the controller report, never model input.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

class InvalidBoundary(RuntimeError):
    pass

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def read(path):
    return json.loads(Path(path).read_text())

def write(path, value):
    path = Path(path)
    tmp = path.with_suffix(path.suffix + '.pending')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    tmp.replace(path)

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def verify_boundary(boundary, jar_hash, turn):
    boundary = Path(boundary)
    manifest = read(boundary / 'manifest.json')
    if manifest['turn'] != turn or manifest['status'] != 'COMPLETED' or manifest['jarSha256'] != jar_hash:
        raise InvalidBoundary('Boundary turn/status/frozen JAR mismatch')
    for name, expected in manifest['sha256'].items():
        f = boundary / name
        if f.is_symlink() or not f.resolve().is_relative_to(boundary.resolve()) or not f.is_file() or sha(f) != expected:
            raise InvalidBoundary('Boundary file mismatch: ' + name)
    if not any(n.startswith('session/') for n in manifest['sha256']):
        raise InvalidBoundary('Missing session inventory')
    return manifest

def copy_inputs(bundle, dest, turn):
    dest.mkdir(parents=True, mode=0o700, exist_ok=False)
    for name in ['seed', 'private-tests']:
        shutil.copytree(bundle / name, dest / name)
    # Frozen artifacts are linked to save disk; their SHA is checked before/after execution.
    os.link(bundle / 'agent.jar', dest / 'agent.jar')
    shutil.copytree(bundle / 'classes', dest / 'classes')
    write(dest / 'stages.json', read(bundle / 'stages.json')[:turn])
    if (bundle / 'disable-verification-basis').exists():
        shutil.copy2(bundle / 'disable-verification-basis', dest / 'disable-verification-basis')

def prepare_attempt(bundle, dest, turn, accepted):
    copy_inputs(bundle, dest, turn)
    if turn == 1:
        write(dest / 'attempt-input.json', {'turn': 1, 'freshSession': True, 'sourceBoundary': None})
        return False
    previous = Path(accepted['directory'])
    boundary = previous / 'boundaries' / ('turn-' + str(turn - 1))
    manifest = verify_boundary(boundary, sha(bundle / 'agent.jar'), turn - 1)
    (dest / 'sessions').mkdir(mode=0o700)
    session = dest / 'sessions' / manifest['sessionId']
    shutil.copytree(boundary / 'session', session, ignore=shutil.ignore_patterns('session.lock'))
    session.chmod(0o700)
    shutil.copytree(previous / 'source', dest / 'source')
    checkpoint = read(session / 'checkpoint.json')
    checkpoint['workspacePath'] = str(session / 'workspace')
    write(session / 'checkpoint.json', checkpoint)
    (session / 'source-workspace.txt').write_text(str(dest / 'source'))
    for name, value in checkpoint['workspaceHashes'].items():
        f = session / 'workspace' / name
        if f.is_symlink() or not f.resolve().is_relative_to((session / 'workspace').resolve()):
            raise InvalidBoundary('Unsafe workspace path')
        if (not f.is_dir() if value == 'directory' else not f.is_file() or sha(f) != value):
            raise InvalidBoundary('Workspace fingerprint mismatch: ' + name)
    report = read(previous / 'report.json')
    if len(report['turns']) != turn - 1 or not all(t.get('status') == 'COMPLETED' and t.get('independentTestsPassed') for t in report['turns']):
        raise InvalidBoundary('Previous accepted chain incomplete')
    for field in ['stopReason', 'hostFailure', 'finishedAt', 'allCompleted', 'allIndependentTestsPassed']:
        report.pop(field, None)
    report.update(status='PREPARED', currentTurn=turn - 1, plannedTurns=turn,
                  method='Automatic previous-boundary retry; same frozen code, model and task; no failure feedback injected')
    write(dest / 'report.json', report)
    write(dest / 'attempt-input.json', {'turn': turn, 'sourceBoundary': str(boundary),
          'verifiedFiles': len(manifest['sha256']), 'sourceManifestSha256': sha(boundary / 'manifest.json'),
          'relocatedPathsOnly': True})
    return True

def costs(directory):
    f = Path(directory) / 'api.jsonl'
    metrics = [json.loads(s) for s in f.read_text().splitlines()] if f.exists() else []
    return {'apiCalls': len(metrics),
            'totalTokens': sum(m.get('usage', {}).get('total_tokens', 0) for m in metrics),
            'promptTokens': sum(m.get('usage', {}).get('prompt_tokens', 0) for m in metrics),
            'completionTokens': sum(m.get('usage', {}).get('completion_tokens', 0) for m in metrics)}

def classify(directory, turn, exit_code):
    path = Path(directory) / 'report.json'
    if exit_code != 0:
        return 'RETRYABLE_PROCESS_FAILURE'
    if not path.exists():
        return 'HARNESS_FAILURE'
    report = read(path)
    row = next((r for r in report.get('turns', []) if r['turn'] == turn), None)
    if not row or row.get('hostFailure') or report.get('hostFailure') or report.get('status') == 'HARNESS_FAILED':
        return 'HARNESS_FAILURE'
    if row['status'] in ['FAILED', 'BUDGET_EXHAUSTED']:
        return 'RETRYABLE_SESSION_FAILURE'
    if row['status'] != 'COMPLETED':
        return 'HARNESS_FAILURE'
    return 'ACCEPTED' if row.get('independentTestsPassed') is True else 'INDEPENDENT_ACCEPTANCE_FAILURE'

def summary(report):
    attempts = [a for t in report['turns'] for a in t['attempts']]
    accepted = [t for t in report['turns'] if t.get('accepted')]
    report['acceptedTurns'] = len(accepted)
    report['firstAttemptPassedTurns'] = sum(t['attempts'][0]['outcome'] == 'ACCEPTED' for t in accepted)
    report['passedAfterRetryTurns'] = sum(len(t['attempts']) > 1 for t in accepted)
    report['retryAttempts'] = sum(max(0, len(t['attempts']) - 1) for t in report['turns'])
    report['totalApiCalls'] = sum(a.get('apiCalls', 0) for a in attempts)
    report['totalTokens'] = sum(a.get('totalTokens', 0) for a in attempts)
    report['failedAttemptTokens'] = sum(a.get('totalTokens', 0) for a in attempts if a['outcome'] != 'ACCEPTED')
    report['continuousFirstAttemptSuccess'] = len(accepted) == report['plannedTurns'] and report['retryAttempts'] == 0
    report['updatedAt'] = now()

def run_suite(bundle, root, turns, max_attempts, run_attempt, config_hash_check=lambda: None):
    bundle, root = Path(bundle).resolve(), Path(root).resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    jar_hash = sha(bundle / 'agent.jar')
    report = {'status': 'RUNNING', 'startedAt': now(), 'plannedTurns': turns,
              'maxAttemptsPerTurn': max_attempts, 'maxModelCallsPerAttempt': 50,
              'cumulativeTokenLimit': None, 'frozenJarSha256': jar_hash,
              'method': 'Automatic bounded previous-checkpoint retries; no code/model/task changes or failure feedback',
              'acceptanceScope': 'Existing independent code contracts; narrative audit completeness is not automatically graded',
              'turns': []}
    previous = None
    try:
        for turn in range(1, turns + 1):
            row = {'turn': turn, 'accepted': False, 'attempts': []}
            report['turns'].append(row)
            for attempt in range(1, max_attempts + 1):
                config_hash_check()
                if sha(bundle / 'agent.jar') != jar_hash:
                    raise InvalidBoundary('Frozen JAR changed')
                directory = root / f'turn-{turn:02d}' / f'attempt-{attempt:02d}'
                resumed = prepare_attempt(bundle, directory, turn, previous)
                item = {'attempt': attempt, 'directory': str(directory), 'startedAt': now(), 'outcome': 'RUNNING',
                        'resumedFromPreviousTurn': resumed}
                row['attempts'].append(item)
                summary(report); write(root / 'report.json', report)
                print(f'START turn={turn} attempt={attempt}', flush=True)
                exit_code = run_attempt(directory, resumed)
                config_hash_check()
                if sha(bundle / 'agent.jar') != jar_hash:
                    raise InvalidBoundary('Frozen JAR changed during attempt')
                item.update(costs(directory)); item.update(finishedAt=now(), exitCode=exit_code,
                    outcome=classify(directory, turn, exit_code))
                if item['outcome'] == 'ACCEPTED':
                    verify_boundary(directory / 'boundaries' / f'turn-{turn}', jar_hash, turn)
                    row.update(accepted=True, acceptedAttempt=attempt, directory=str(directory))
                    previous = row
                print(f'END turn={turn} attempt={attempt} outcome={item["outcome"]} tokens={item["totalTokens"]}', flush=True)
                summary(report); write(root / 'report.json', report)
                if row['accepted']:
                    break
                if item['outcome'] not in ['RETRYABLE_SESSION_FAILURE', 'RETRYABLE_PROCESS_FAILURE']:
                    report.update(status='STOPPED', stopReason=item['outcome']); break
            if not row['accepted']:
                report.update(status='STOPPED', stopReason=report.get('stopReason', 'RETRY_LIMIT_REACHED')); break
        else:
            report['status'] = 'ALL_TURNS_ACCEPTED_WITH_RETRY_POLICY'
    except Exception as e:
        report.update(status='HARNESS_FAILED', stopReason=type(e).__name__)
        raise
    finally:
        summary(report); report['finishedAt'] = now(); write(root / 'report.json', report)
    return report

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--max-attempts', type=int, default=3)
    parser.add_argument('--turns', type=int, default=64)
    args = parser.parse_args()
    if not 1 <= args.max_attempts <= 10 or not 1 <= args.turns <= len(read(args.bundle / 'stages.json')):
        parser.error('Invalid attempt or turn limit')
    config = args.config.resolve(); config_hash = sha(config)
    def check():
        if sha(config) != config_hash:
            raise InvalidBoundary('Model configuration changed; stop instead of silently changing the experiment')
    def invoke(directory, resumed):
        command = [str(args.java.resolve()), '-cp', str(directory / 'classes') + os.pathsep + str(directory / 'agent.jar'),
                   'dev.backendagent.model.CheckpointRetryEvaluation', str(directory), str(config)]
        if resumed:
            command.append('--resume-completed')
        with (directory / 'process.log').open('w') as log:
            return subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False).returncode
    report = run_suite(args.bundle, args.output, args.turns, args.max_attempts, invoke, check)
    return 0 if report['status'] == 'ALL_TURNS_ACCEPTED_WITH_RETRY_POLICY' else 1

if __name__ == '__main__':
    sys.exit(main())
