"""读取当前任务的权威字段档案；缺失不能降级成索引里的历史分数。"""
from .java_gateway import _post, JavaAttemptError

async def read_profiles(task_id: str, resources: list[dict]) -> list[dict]:
    result = await _post(f'/internal/iam-s1/query/tasks/{task_id}/field-profiles', {'resources':resources})
    profiles=result.get('profiles')
    expected={f"{table['tableName']}.{column}" for table in resources for column in table['columns']}
    if (not isinstance(profiles,list) or any(not isinstance(p,dict) for p in profiles)
            or {p.get('fieldName') for p in profiles}!=expected or len(profiles)!=len(expected)):
        raise JavaAttemptError('字段档案范围不完整，不能使用历史分数继续问数')
    return profiles
