"""Prepare a frozen, local evaluation bundle; no model requests are sent here."""
import argparse
import collections
import json
import shutil
import subprocess
from pathlib import Path


def expected(lines):
    counts=collections.Counter(); delays=[]
    for line in lines:
        if not line.strip():continue
        try:
            parts=line.split('|')
            assert len(parts)==4
            status=int(parts[2]); delay=int(parts[3]); timestamp=int(parts[0])
            assert 100<=status<=599 and 0<=delay<=3600000 and timestamp>=0
        except (ValueError,AssertionError):
            counts['invalid']+=1;continue
        counts['valid']+=1;counts[status]+=1;delays.append(delay)
    valid=counts['valid'];c2=sum(counts[s] for s in range(200,300));c4=sum(counts[s] for s in range(400,500));c5=sum(counts[s] for s in range(500,600))
    return [valid,counts['invalid'],c2,c4,c5,sum(delays),sorted(delays)[(95*valid+99)//100-1] if valid else 0,
            int(valid>=3 and (c4+c5-counts[429])*10000>=2000*valid)]


def prepare(fixtures, destination, jar, java_source, javac):
    fixtures,destination,jar=Path(fixtures).resolve(),Path(destination).resolve(),Path(jar).resolve()
    destination.mkdir(parents=True,mode=0o700,exist_ok=False)
    shutil.copytree(fixtures/'seed',destination/'seed')
    shutil.copy2(fixtures/'stages.json',destination/'stages.json')
    shutil.copy2(jar,destination/'agent.jar')
    (destination/'disable-verification-basis').write_text('Same basis-disabled protocol as the recorded 64-round experiment.\n')
    graders=destination/'private-tests';graders.mkdir()
    for stage in range(1,7):
        shutil.copy2(fixtures/f'grader-templates/stage-{min(stage,5)}.java.txt',graders/f'stage-{stage}.java')
    prefix=(fixtures/'grader-templates/replay-prefix.java.txt').read_text()
    tasks=json.loads((fixtures/'stages.json').read_text())
    for stage,task in enumerate(tasks[6:],start=7):
        lines=task.split('```text\n',1)[1].split('\n```',1)[0].splitlines()
        source=prefix+f' @Test void round{stage}FixtureAndBehaviour() throws Exception {{\n'
        source+='  var lines=java.nio.file.Files.readAllLines(java.nio.file.Path.of("data/replays/current.txt"));\n'
        source+='  assertEquals(java.util.List.of('+','.join(json.dumps(line,ensure_ascii=False) for line in lines)+'),lines);\n'
        source+='  assertArrayEquals(new long[]{'+','.join(str(v)+'L' for v in expected(lines))+'},LogAnalyzer.analyze(lines));\n }\n}\n'
        (graders/f'stage-{stage}.java').write_text(source)
    subprocess.run([str(Path(javac).resolve()),'-cp',str(destination/'agent.jar'),'-d',str(destination/'classes'),str(Path(java_source).resolve())],check=True)
    return destination


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--javac',type=Path,required=True)
    args=parser.parse_args();root=Path(__file__).resolve().parents[2]
    result=prepare(root/'fixtures/long-context',args.output,args.jar,
        root/'src/test/java/dev/backendagent/model/CheckpointRetryEvaluation.java',args.javac)
    print('Prepared evaluation bundle:',result)

if __name__=='__main__':main()
