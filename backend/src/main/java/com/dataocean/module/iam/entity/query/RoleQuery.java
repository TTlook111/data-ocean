package com.dataocean.module.iam.entity.query;
import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import java.util.List;
public class RoleQuery extends PageQuery {
    private String name = null;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public List<String> getNameTerms() { return TextSearch.terms(name); }
    private String lifecycle = null;
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String value) { lifecycle = value; }
    @Override protected java.util.Map<String,String> sortColumns() { return java.util.Map.of("updatedAt", "r.updated_at", "name", "r.name_key"); }
}
