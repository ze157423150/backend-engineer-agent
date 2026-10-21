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


def prepare(fixtures, destination, jar, java_source, javac, tool_selection="all", turns=None, max_model_calls=50, tool_search_ranker="lexical"):
    if not 1 <= max_model_calls <= 50:
        raise ValueError("Invalid model call limit")
    if tool_selection not in {"all", "rule"}:
        raise ValueError("Unknown tool selection mode")
    if tool_search_ranker not in {"lexical", "bm25"} or tool_search_ranker == "bm25" and tool_selection != "rule":
        raise ValueError("BM25 requires rule selection; unknown tool search ranker")
    fixtures,destination,jar=Path(fixtures).resolve(),Path(destination).resolve(),Path(jar).resolve()
    destination.mkdir(parents=True,mode=0o700,exist_ok=False)
    if tool_selection == 'rule':
        (destination/'tool-selection-rule').write_text('Enable production rule selector and tool discovery.\n')
    (destination/'tool-search-ranker.txt').write_text(tool_search_ranker+'\n')
    shutil.copytree(fixtures/'seed',destination/'seed')
    all_tasks=json.loads((fixtures/'stages.json').read_text())
    if turns is not None and not 1 <= turns <= len(all_tasks):
        raise ValueError('Invalid turn count')
    (destination/'stages.json').write_text(json.dumps(all_tasks[:turns],ensure_ascii=False,indent=2)+'\n')
    (destination/'model-call-limit.txt').write_text(str(max_model_calls)+'\n')
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
    parser.add_argument('--tool-selection',choices=['all','rule'],default='all')
    parser.add_argument('--tool-search-ranker',choices=['lexical','bm25'],default='lexical')
    parser.add_argument('--turns',type=int)
    parser.add_argument('--max-model-calls-per-turn',type=int,default=50)
    args=parser.parse_args();root=Path(__file__).resolve().parents[2]
    result=prepare(root/'fixtures/long-context',args.output,args.jar,
        root/'src/test/java/dev/backendagent/model/CheckpointRetryEvaluation.java',args.javac,args.tool_selection,args.turns,args.max_model_calls_per_turn,args.tool_search_ranker)
    print('Prepared evaluation bundle:',result)

if __name__=='__main__':main()
