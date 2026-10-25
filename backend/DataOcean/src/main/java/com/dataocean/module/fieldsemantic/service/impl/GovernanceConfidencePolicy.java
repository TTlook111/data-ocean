package com.dataocean.module.fieldsemantic.service.impl;

/** 使用证据参考分：中性先验避免单次评价产生满分；没有证据保持未知。 */
public final class GovernanceConfidencePolicy {
    public record Counts(long positive,long negative) {}
    public static Counts summarize(java.util.List<com.dataocean.module.fieldsemantic.entity.po.GovernanceConfidenceEvent> events) {
        var latest=new java.util.HashMap<String,com.dataocean.module.fieldsemantic.entity.po.GovernanceConfidenceEvent>();
        for(var event:events){String key=event.getEvidenceKey()==null?"legacy:"+event.getId():event.getEvidenceKey();
            latest.merge(key,event,(a,b)->a.getId()>b.getId()?a:b);}
        long positive=latest.values().stream().filter(e->"LIKE".equals(e.getDirection())).count();
        return new Counts(positive,latest.size()-positive);
    }
    private GovernanceConfidencePolicy() {}
    public static Integer score(long positive,long negative) {
        if(positive<0||negative<0)throw new IllegalArgumentException("证据数不能为负");
        if(positive+negative==0)return null;
        return (int)Math.round(100.0*(positive+1)/(positive+negative+2));
    }
}
