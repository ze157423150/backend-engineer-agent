import tempfile
import unittest
import json
from pathlib import Path
import checkpoint_retry as c

class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name); self.bundle = self.base/'bundle'; self.bundle.mkdir()
        (self.bundle/'seed').mkdir(); (self.bundle/'seed'/'Main.java').write_text('original')
        (self.bundle/'private-tests').mkdir(); (self.bundle/'classes').mkdir()
        (self.bundle/'agent.jar').write_bytes(b'frozen')
        c.write(self.bundle/'stages.json', ['first task', 'second task'])
        (self.bundle/'disable-verification-basis').touch()

    def test_tool_selection_mode_is_preserved_across_turns_and_retry(self):
        (self.bundle/'tool-selection-rule').write_text('rule')
        (self.bundle/'model-call-limit.txt').write_text('20')
        (self.bundle/'tool-search-ranker.txt').write_text('bm25')
        def invoke(directory, resumed):
            self.assertTrue((directory/'tool-selection-rule').exists())
            self.assertEqual('20',(directory/'model-call-limit.txt').read_text())
            self.assertEqual('bm25',(directory/'tool-search-ranker.txt').read_text())
            turn = len(c.read(directory/'stages.json'))
            first = directory.name == 'attempt-01'
            return self.finish(directory, 'FAILED' if turn == 2 and first else 'COMPLETED')
        report = c.run_suite(self.bundle,self.base/'selected-run',2,2,invoke)
        self.assertEqual('rule',report['toolSelection'])
        self.assertEqual(20,report['maxModelCallsPerAttempt'])
        self.assertEqual('bm25',report['toolSearchRanker'])
        self.assertEqual(2,report['acceptedTurns'])
        self.assertEqual(1,report['retryAttempts'])

    def test_default_copy_does_not_enable_selection(self):
        directory = self.base/'plain-attempt'
        c.copy_inputs(self.bundle,directory,1)
        self.assertFalse((directory/'tool-selection-rule').exists())

    def finish(self, directory, status='COMPLETED', passed=True, tokens=10):
        tasks=c.read(directory/'stages.json'); turn=len(tasks)
        report=c.read(directory/'report.json') if (directory/'report.json').exists() else {'turns':[], 'sessionId':'id', 'externalApiCalls':0,'providerReportedTokens':0}
        if not (directory/'source').exists(): c.shutil.copytree(directory/'seed',directory/'source')
        session=directory/'sessions'/'id'; workspace=session/'workspace'
        if not workspace.exists():
            workspace.mkdir(parents=True); (workspace/'Main.java').write_text('original')
        checkpoint={'workspacePath':str(workspace),'workspaceHashes':{'Main.java':c.sha(workspace/'Main.java')}}
        c.write(session/'checkpoint.json',checkpoint);(session/'source-workspace.txt').write_text(str(directory/'source'))
        (session/'events.jsonl').write_text('preserved history')
        report['turns'].append({'turn':turn,'status':status,'independentTestsPassed':passed})
        report.update(status='DONE',externalApiCalls=report['externalApiCalls']+1,providerReportedTokens=report['providerReportedTokens']+tokens)
        c.write(directory/'report.json',report)
        (directory/'api.jsonl').write_text(json.dumps({'usage':{'total_tokens':tokens,'prompt_tokens':tokens-1,'completion_tokens':1}})+'\n')
        boundary=directory/'boundaries'/f'turn-{turn}';c.shutil.copytree(session,boundary/'session')
        manifest={'turn':turn,'status':status,'sessionId':'id','jarSha256':c.sha(directory/'agent.jar'),
                  'sha256':{str(f.relative_to(boundary)):c.sha(f) for f in boundary.rglob('*') if f.is_file()}}
        c.write(boundary/'manifest.json',manifest)
        return 0

    def test_failed_attempt_is_discarded_but_costs_and_successful_prefix_are_retained(self):
        seen=[]
        def invoke(d,resumed):
            turn=len(c.read(d/'stages.json')); attempt=int(d.name.split('-')[1]);seen.append((turn,attempt,resumed))
            if turn==1:return self.finish(d,tokens=10)
            # Both attempts must start with exactly the accepted previous workspace and history.
            self.assertEqual('original',(d/'sessions/id/workspace/Main.java').read_text())
            self.assertEqual('preserved history',(d/'sessions/id/events.jsonl').read_text())
            self.assertEqual(1,len(c.read(d/'report.json')['turns']))
            if attempt==1:
                result=self.finish(d,'FAILED',False,tokens=30)
                (d/'sessions/id/workspace/Main.java').write_text('failed edit must not carry forward')
                return result
            return self.finish(d,tokens=20)
        result=c.run_suite(self.bundle,self.base/'run',2,3,invoke)
        self.assertEqual([(1,1,False),(2,1,True),(2,2,True)],seen)
        self.assertEqual('ALL_TURNS_ACCEPTED_WITH_RETRY_POLICY',result['status'])
        self.assertEqual(1,result['firstAttemptPassedTurns']);self.assertEqual(1,result['passedAfterRetryTurns'])
        self.assertEqual(60,result['totalTokens']);self.assertEqual(30,result['failedAttemptTokens'])
        self.assertFalse(result['continuousFirstAttemptSuccess'])
        self.assertTrue((self.base/'run/turn-02/attempt-01/sessions/id/workspace/Main.java').exists())

    def test_first_turn_retry_starts_from_seed_without_failed_session(self):
        def invoke(d,resumed):
            self.assertFalse(resumed);self.assertFalse((d/'sessions').exists())
            return self.finish(d,'FAILED' if d.name=='attempt-01' else 'COMPLETED',tokens=10)
        result=c.run_suite(self.bundle,self.base/'run',1,2,invoke)
        self.assertEqual(1,result['acceptedTurns']);self.assertEqual(1,result['retryAttempts'])

    def test_budget_exhaustion_has_bounded_retries(self):
        result=c.run_suite(self.bundle,self.base/'run',1,3,lambda d,resumed:self.finish(d,'BUDGET_EXHAUSTED',False))
        self.assertEqual('RETRY_LIMIT_REACHED',result['stopReason']);self.assertEqual(3,result['totalApiCalls'])
        self.assertEqual(0,result['acceptedTurns'])

    def test_completed_but_failed_independent_acceptance_retries_with_same_bound(self):
        result=c.run_suite(self.bundle,self.base/'run',1,3,lambda d,resumed:self.finish(d,passed=False))
        self.assertEqual('RETRY_LIMIT_REACHED',result['stopReason'])
        self.assertEqual(3,result['totalApiCalls'])
        self.assertEqual(0,result['acceptedTurns'])
        self.assertTrue(all(a['outcome']=='INDEPENDENT_ACCEPTANCE_FAILURE'
                            for a in result['turns'][0]['attempts']))

    def test_acceptance_retry_discards_failed_completed_workspace_and_retains_cost(self):
        seen=[]
        def invoke(d,resumed):
            turn=len(c.read(d/'stages.json'));attempt=int(d.name.split('-')[1])
            seen.append((turn,attempt))
            if turn==1:return self.finish(d,tokens=10)
            self.assertTrue(resumed)
            self.assertEqual('original',(d/'sessions/id/workspace/Main.java').read_text())
            self.assertEqual('preserved history',(d/'sessions/id/events.jsonl').read_text())
            self.assertEqual(1,len(c.read(d/'report.json')['turns']))
            if attempt==1:
                result=self.finish(d,passed=False,tokens=30)
                (d/'sessions/id/workspace/Main.java').write_text('completed but incorrect output')
                return result
            return self.finish(d,tokens=20)
        result=c.run_suite(self.bundle,self.base/'run',2,3,invoke)
        self.assertEqual([(1,1),(2,1),(2,2)],seen)
        self.assertEqual('ALL_TURNS_ACCEPTED_WITH_RETRY_POLICY',result['status'])
        self.assertEqual(2,result['acceptedTurns'])
        self.assertEqual(60,result['totalTokens'])
        self.assertEqual(30,result['failedAttemptTokens'])
        self.assertEqual('INDEPENDENT_ACCEPTANCE_FAILURE',result['turns'][1]['attempts'][0]['outcome'])
        self.assertEqual('completed but incorrect output',
            (self.base/'run/turn-02/attempt-01/sessions/id/workspace/Main.java').read_text())

    def test_missing_acceptance_result_stops_without_retry(self):
        def invoke(d,resumed):
            self.finish(d)
            report=c.read(d/'report.json')
            report['turns'][-1].pop('independentTestsPassed')
            c.write(d/'report.json',report)
            return 0
        result=c.run_suite(self.bundle,self.base/'run',1,3,invoke)
        self.assertEqual('HARNESS_FAILURE',result['stopReason'])
        self.assertEqual(1,result['totalApiCalls'])

    def test_first_turn_acceptance_retry_uses_seed_instead_of_failed_workspace(self):
        def invoke(d,resumed):
            self.assertFalse(resumed)
            self.assertFalse((d/'sessions').exists())
            self.assertEqual('original',(d/'seed/Main.java').read_text())
            return self.finish(d,passed=d.name!='attempt-01')
        result=c.run_suite(self.bundle,self.base/'run',1,3,invoke)
        self.assertEqual(1,result['acceptedTurns'])
        self.assertEqual(1,result['retryAttempts'])

    def test_corrupt_accepted_boundary_stops_before_next_model_call(self):
        def invoke(d,resumed):
            code=self.finish(d)
            (d/'boundaries/turn-1/session/events.jsonl').write_text('corrupt')
            return code
        with self.assertRaises(c.InvalidBoundary):c.run_suite(self.bundle,self.base/'run',2,3,invoke)
        self.assertEqual('HARNESS_FAILED',c.read(self.base/'run/report.json')['status'])

    def test_unchanged_config_is_checked_before_and_after_every_attempt(self):
        checks=[]
        c.run_suite(self.bundle,self.base/'run',1,2,lambda d,resumed:self.finish(d),lambda:checks.append(True))
        self.assertEqual(2,len(checks))

    def test_process_exit_is_retried_without_reusing_its_workspace(self):
        def invoke(d,resumed):
            code=self.finish(d)
            if d.name=='attempt-01':
                (d/'sessions/id/workspace/Main.java').write_text('interrupted')
                return 137
            self.assertEqual('original',(d/'sessions/id/workspace/Main.java').read_text())
            return code
        result=c.run_suite(self.bundle,self.base/'run',1,2,invoke)
        self.assertEqual('ALL_TURNS_ACCEPTED_WITH_RETRY_POLICY',result['status'])
        self.assertEqual('RETRYABLE_PROCESS_FAILURE',result['turns'][0]['attempts'][0]['outcome'])
        self.assertEqual(20,result['totalTokens'])

    def test_cli_returns_failure_when_suite_stops(self):
        from unittest.mock import patch
        import sys
        config=self.base/'local.properties';config.write_text('local test fixture')
        argv=['checkpoint_retry','--bundle',str(self.bundle),'--output',str(self.base/'run'),
              '--config',str(config),'--java',sys.executable,'--turns','1']
        for status,expected_code in [('STOPPED',1),('ALL_TURNS_ACCEPTED_WITH_RETRY_POLICY',0)]:
            with self.subTest(status=status),patch.object(sys,'argv',argv),patch.object(c,'run_suite',return_value={'status':status}):
                self.assertEqual(expected_code,c.main())

    def test_existing_output_is_never_overwritten(self):
        dest=self.base/'run';dest.mkdir();(dest/'keep').write_text('keep')
        with self.assertRaises(FileExistsError):c.run_suite(self.bundle,dest,1,2,lambda d,resumed:0)
        self.assertEqual('keep',(dest/'keep').read_text())

if __name__=='__main__':unittest.main()
