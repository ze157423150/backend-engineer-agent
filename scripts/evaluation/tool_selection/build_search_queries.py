"""Build explicit capability-query samples, separate from whole-task selector replay."""
import argparse,json,hashlib
from pathlib import Path


def build(root, output):
    output.mkdir(parents=True,exist_ok=False)
    samples={}
    def add(query, expected, source):
        # Expected groups are intent labels, never old search-result rankings.
        previous=samples.get(query)
        if previous:
            if previous['expectedAny']!=expected:raise ValueError('Conflicting labels')
            previous['sources'].append(source)
        else:samples[query]={'id':f'history-{len(samples)+1:02d}','group':'historical','query':query,'expectedAny':expected,'sources':[source]}
    for name in ['tool-discovery-live-20261020-001','tool-discovery-live-20261020-002']:
        source=root/'reports'/name/'report.json'
        for run in json.loads(source.read_text())['runs']:
            for exchange in run['exchanges']:
                if exchange['call']['name']!='search_tools':continue
                q=exchange['call']['arguments']['query']
                if q=='nebula_quasar_capability':expected=[]
                else:expected=['search_code']  # All these archived microtasks explicitly request file-content search.
                add(q,expected,{'path':str(source.relative_to(root)),'run':run['id'],'callId':exchange['call']['id']})
    for name in ['tool-selection-six-turn-20261020-001','tool-selection-six-turn-20261020-002']:
        source=root/'reports'/name/'对照统计.json'
        for row in json.loads(source.read_text())['modes']['selected']['turns']:
            for entry in row['toolSearches']:
                q=entry['query']
                if 'run_tests' not in q:raise ValueError('Unlabelled historical intent: '+q)
                add(q,['run_tests'],{'path':str(source.relative_to(root)),'turn':row['turn']})
    curated=[
        ('读取文件某一段源码',['read_file']),('列出目录下有哪些文件',['list_files']),
        ('按关键词搜索代码并返回位置',['search_code']),('创建一个不存在的文件',['create_file']),
        ('精确替换现有文件中的一段代码',['apply_patch','apply_patch_batch']),
        ('同一文件多处不重叠补丁一起修改',['apply_patch_batch']),('查看当前修改了哪些文件',['workspace_diff']),
        ('运行 Maven 单元测试',['run_tests']),('执行整数算式运算',['integer_calculate']),
        ('按关键词查找以前的工具调用',['search_history']),('根据 call_id 提取过往工具结果',['read_observation']),
        ('查找过去用户对话轮次',['search_turns']),('打开指定的历史用户轮次',['read_turn']),
        ('保存一条工作记忆',['remember_fact']),('访问外网搜索天气预报',[]),('查询数据库中的用户记录',[])]
    cases=list(samples.values())+[{'id':f'curated-{i:02d}','group':'curated','query':q,'expectedAny':e,'sources':[]} for i,(q,e) in enumerate(curated,1)]
    (output/'queries.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n')
    catalog=root/'reports/tool-selector-offline-20261020-001/verified-samples/catalog.json'
    (output/'catalog.json').write_bytes(catalog.read_bytes())
    (output/'manifest.json').write_text(json.dumps({'historicalUniqueQueries':len(samples),'curatedQueries':len(curated),'toolCount':len(json.loads(catalog.read_text())),'catalogSha256':hashlib.sha256(catalog.read_bytes()).hexdigest(),'labels':'Manual intent mapping from task/query, not copying baseline search results; expectedAny contains acceptable alternatives','limitations':'Historical queries mostly code search and run_tests; curated cases are supplemental probes, not held-out production data'},ensure_ascii=False,indent=2)+'\n')
    print('Prepared',len(cases),'queries;',len(samples),'historical,',len(curated),'curated')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    build(Path(__file__).resolve().parents[3],a.output)
