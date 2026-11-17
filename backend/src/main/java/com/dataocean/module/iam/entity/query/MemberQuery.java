package com.dataocean.module.iam.entity.query;
import com.dataocean.common.PageQuery;
import com.dataocean.common.TextSearch;
import java.util.List;
public class MemberQuery extends PersonQuery {
    private boolean includeDescendants = true;
    public boolean getIncludeDescendants() { return includeDescendants; }
    public void setIncludeDescendants(boolean value) { includeDescendants = value; }
    @Override protected java.util.Map<String,String> sortColumns() { return java.util.Map.of("updatedAt", "u.updated_at", "username", "u.username", "displayName", "u.display_name"); }
}
