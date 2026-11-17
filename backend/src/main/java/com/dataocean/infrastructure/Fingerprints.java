package com.dataocean.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 为已绑定的明确 DTO 生成规范指纹，不接收外部任意 JSON 业务写入。 */
@Component
@RequiredArgsConstructor
public class Fingerprints {
    private static final Set<String> REVIEW_OMISSIONS=Set.of("expectedVersion","reviewToken","reset","password","currentPassword","initialPassword","newPassword","confirmPassword");
    private final JsonCodec json;
    private final SecretKeyProvider secrets;
    public String canonical(Object value,boolean review) { return canonicalNode(json.mapper().valueToTree(value),review); }
    public byte[] command(String operation,String target,Object value) { return secrets.hmac(operation+"|"+target+"|"+canonical(value,false)); }
    public String proposal(Object value) { return java.util.HexFormat.of().formatHex(secrets.hmac(canonical(value,true))); }
    private String canonicalNode(JsonNode node,boolean review) {
        if (node.isObject()) {
            var fields=new ArrayList<String>();
            node.properties().forEach(entry->{
                if (!entry.getKey().equals("reviewToken") && (!review || !REVIEW_OMISSIONS.contains(entry.getKey())))
                    fields.add(json.write(entry.getKey())+":"+canonicalNode(entry.getValue(),review));
            });
            fields.sort(Comparator.naturalOrder());
            return "{"+String.join(",",fields)+"}";
        }
        if (node.isArray()) {
            var values=new ArrayList<String>();
            node.forEach(item->values.add(canonicalNode(item,review)));
            return "["+String.join(",",values)+"]";
        }
        return node.toString();
    }
}
