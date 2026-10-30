package com.dataocean.module.fieldsemantic.service.impl;

import com.dataocean.common.exception.BusinessException;
import java.util.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

public final class BusinessKnowledgePolicy {
    private BusinessKnowledgePolicy() {}
    public static final Set<String> KINDS = Set.of("DATASOURCE", "TABLE", "RELATION", "METRIC");
    public static void requireDependencies(String kind,Map<String,String> body,List<String> dependencies){
        if(!Set.of("RELATION","METRIC").contains(kind))return;
        String formula=Objects.toString(body.get("formula"),"").replaceAll("\\s","").toUpperCase(Locale.ROOT);
        String conditions=Objects.toString(body.get("conditions"),"").replaceAll("\\s","");
        if("METRIC".equals(kind)&&dependencies.stream().noneMatch(d->d.startsWith("column:"))&&!formula.isBlank()){
            if(!Set.of("COUNT(*)","COUNT(1)").contains(formula)||!conditions.isBlank()&&!Set.of("无","无额外条件","无额外筛选","无额外筛选条件","NONE","none","TRUE","true","1=1").contains(conditions))
                throw new BusinessException("除无额外条件的行计数外，指标应提供完整字段依赖；无筛选条件请填写“无”");
        }
        for(String key:List.of("joinCondition","formula","conditions")){
            String expression=Objects.toString(body.get(key),"").trim();
            if(expression.isEmpty()||key.equals("conditions")&&Set.of("无","无额外条件","无额外筛选","无额外筛选条件","NONE").contains(expression.toUpperCase(Locale.ROOT)))continue;
            if(key.equals("formula")&&Set.of("COUNT(*)","COUNT(1)").contains(expression.replaceAll("\\s","").toUpperCase(Locale.ROOT)))continue;
            validateExpression(expression,dependencies);
        }
    }
    /** 使用既有解析器验证表达式；首期明确拒绝别名、子查询及未限定字段，不猜测资源绑定。 */
    private static void validateExpression(String value,List<String> dependencies){
        try{
            if(value.length()>4000)throw new BusinessException("业务表达式过长，请拆分主题");
            int nesting=0;char quote=0;
            for(int i=0;i<value.length();i++){char c=value.charAt(i);
                if(quote!=0){if(c==92){i++;continue;}if(c==quote){if(i+1<value.length()&&value.charAt(i+1)==quote)i++;else quote=0;}continue;}
                if(c==39||c==34||c==96){quote=c;continue;}
                if(c=='('&&++nesting>32)throw new BusinessException("业务表达式嵌套过于复杂，请拆分主题");
                if(c==')')nesting--;
            }
            var expression=net.sf.jsqlparser.parser.CCJSqlParserUtil.parseCondExpression(value,false);
            validateNode(expression,dependencies,0,new int[]{0});
        }catch(BusinessException exception){throw exception;}
        catch(Exception exception){throw new BusinessException("业务表达式无法完整解析，请使用明确的 SQL 表达式；业务说明请填写使用限制");}
    }
    private static void validateNode(Expression node,List<String> dependencies,int depth,int[] nodes){
        if(node==null)return;
        if(depth>32||++nodes[0]>256)throw new BusinessException("业务表达式过于复杂，请拆分主题");
        if(node instanceof Column column){
            String table=column.getTable()==null?"":column.getTable().getFullyQualifiedName().replace("`","");
            String name=column.getColumnName().replace("`","");
            if(table.isBlank()||table.contains(".")||!dependencies.contains("table:"+table)||!dependencies.contains("column:"+table+"."+name))
                throw new BusinessException("业务表达式完整依赖不明确，请使用真实表名.字段名并声明所属表及字段");
        }else if(node instanceof Function function){
            String name=function.getName().toUpperCase(Locale.ROOT);
            if(!Set.of("COUNT","SUM","AVG","MIN","MAX","COALESCE","NULLIF","ROUND","ABS","IF").contains(name)
                    ||function.getNamedParameters()!=null||function.getAttribute()!=null||function.getLimit()!=null||function.getHavingClause()!=null
                    ||function.getOrderByElements()!=null&&!function.getOrderByElements().isEmpty())throw unsupportedExpression();
            if(function.getParameters()!=null)for(var parameter:function.getParameters()){
                if(parameter instanceof net.sf.jsqlparser.statement.select.AllColumns&&name.equals("COUNT"))continue;
                validateNode(parameter,dependencies,depth+1,nodes);
            }
        }else if(node instanceof BinaryExpression binary){
            if(!Set.of("Addition","Subtraction","Multiplication","Division","IntegerDivision","Modulo","AndExpression","OrExpression",
                    "EqualsTo","NotEqualsTo","GreaterThan","GreaterThanEquals","MinorThan","MinorThanEquals","LikeExpression").contains(node.getClass().getSimpleName()))throw unsupportedExpression();
            validateNode(binary.getLeftExpression(),dependencies,depth+1,nodes);validateNode(binary.getRightExpression(),dependencies,depth+1,nodes);
        }else if(node instanceof CaseExpression conditional){
            validateNode(conditional.getSwitchExpression(),dependencies,depth+1,nodes);validateNode(conditional.getElseExpression(),dependencies,depth+1,nodes);
            for(var branch:conditional.getWhenClauses()){validateNode(branch.getWhenExpression(),dependencies,depth+1,nodes);validateNode(branch.getThenExpression(),dependencies,depth+1,nodes);}
        }else if(node instanceof ExpressionList<?> list){for(var item:list)validateNode(item,dependencies,depth+1,nodes);
        }else if(node instanceof SignedExpression signed){validateNode(signed.getExpression(),dependencies,depth+1,nodes);
        }else if(node instanceof NotExpression not){validateNode(not.getExpression(),dependencies,depth+1,nodes);
        }else if(node instanceof IsNullExpression isNull){validateNode(isNull.getLeftExpression(),dependencies,depth+1,nodes);
        }else if(node instanceof InExpression in){validateNode(in.getLeftExpression(),dependencies,depth+1,nodes);validateNode(in.getRightExpression(),dependencies,depth+1,nodes);
        }else if(node instanceof Between between){validateNode(between.getLeftExpression(),dependencies,depth+1,nodes);validateNode(between.getBetweenExpressionStart(),dependencies,depth+1,nodes);validateNode(between.getBetweenExpressionEnd(),dependencies,depth+1,nodes);
        }else if(!(node instanceof LongValue||node instanceof DoubleValue||node instanceof StringValue||node instanceof NullValue||node instanceof DateValue||node instanceof TimeValue||node instanceof TimestampValue))throw unsupportedExpression();
    }
    private static BusinessException unsupportedExpression(){return new BusinessException("业务表达式暂不支持此语法、子查询、变量或窗口，请使用明确的表字段表达式");}
    public static void requireReady(String kind, Map<String, String> body, List<String> unresolved) {
        if (!KINDS.contains(kind)) throw new BusinessException("不支持的业务知识类型");
        if (unresolved != null && !unresolved.isEmpty()) throw new BusinessException("仍有待确认内容，不能提交为已确认知识");
        List<String> required = switch (kind) {
            case "TABLE" -> List.of("description", "grain");
            case "RELATION" -> List.of("description", "joinCondition", "cardinality", "aggregationNotes");
            case "METRIC" -> List.of("description", "formula", "conditions");
            default -> List.of("description", "scope");
        };
        for (String key : required) if (body == null || body.get(key) == null || body.get(key).isBlank())
            throw new BusinessException("业务知识缺少必要内容：" + Map.of("description","业务说明","scope","适用业务范围","grain","表粒度", "joinCondition","完整关联条件",
                    "cardinality","关系基数","aggregationNotes","聚合与重复计数说明","formula","计算公式","conditions","必要筛选条件").get(key));
    }
}
