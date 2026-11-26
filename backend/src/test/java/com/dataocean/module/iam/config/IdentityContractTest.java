package com.dataocean.module.iam.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dataocean.common.TextSearch;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.iam.entity.dto.CreateRoleDTO;
import com.dataocean.module.iam.entity.dto.CreateUserDTO;
import com.dataocean.module.iam.entity.vo.MembershipRefVO;
import com.dataocean.module.iam.entity.vo.RoleRowVO;
import com.dataocean.module.iam.service.PasswordPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 验证字符计量、完整密码散列、字面查询和秘密指纹的契约边界。
 */
class IdentityContractTest {

    /**
     * 测试密钥仅存在于由测试框架清理的临时目录。
     */
    @TempDir
    Path temporary;

    /**
     * 平面 JSON 复用父类型字段后仍能正确绑定，并继续拒绝客户端夹带的系统字段。
     */
    @Test
    void inheritedInputKeepsFlatStrictJsonContract() {
        var json = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        String body =
                "{\"username\":\"test-user\",\"displayName\":\"测试人员\",\"membership\":{\"primaryDepartmentId\":\"1\",\"secondaryDepartmentIds\":[]},\"roles\":[],\"initialPassword\":\"Synthetic123\",\"reviewToken\":\"test-reference\"}";

        var input = json.readValue(body, CreateUserDTO.class);
        assertEquals("test-user", input.getUsername());
        assertEquals("1", input.getMembership().getPrimaryDepartmentId());
        assertEquals("Synthetic123", input.getInitialPassword());
        assertThrows(
                JacksonException.class,
                () -> json.readValue(
                        body.substring(0, body.length() - 1) + ",\"userId\":\"99\"}", CreateUserDTO.class));
    }

    /**
     * 布尔属性遵循已定对外字段名，不因 Lombok 访问器重复产生另一份属性。
     */
    @Test
    void externalBooleanNamesMatchContract() {
        var json = JsonMapper.builder().build();

        var role = new RoleRowVO();
        role.setBuiltin(true);

        var roleTree = json.valueToTree(role);
        assertTrue(roleTree.has("isBuiltin"));
        assertFalse(roleTree.has("builtin"));

        var membership = new MembershipRefVO();
        membership.setPrimary(true);

        var membershipTree = json.valueToTree(membership);
        assertTrue(membershipTree.has("isPrimary"));
        assertFalse(membershipTree.has("primary"));
    }

    /**
     * 合法多字节密码超过 72 字节时仍完整参与密码散列。
     */
    @Test
    void encodesCompleteMultibytePassword() {
        String password = "测".repeat(28) + "A1";
        PasswordPolicy.requireStrong(password);

        var encoder = new IamSecurityConfiguration().passwordEncoder();

        String encoded = encoder.encode(password);
        assertTrue(encoder.matches(password, encoded));
        assertFalse(encoder.matches("测".repeat(28) + "A2", encoded));
        assertThrows(BizIllegalException.class, () -> PasswordPolicy.requireStrong("onlyletters"));
        assertThrows(BizIllegalException.class, () -> PasswordPolicy.requireStrong("12345678"));
        assertThrows(BizIllegalException.class, () -> PasswordPolicy.requireStrong("短A1"));
    }

    /**
     * DTO 复用后保留父类型校验，长度按 Unicode 码点而非 UTF-16 单元计算。
     */
    @Test
    void inheritedProfileValidatesUnicodeCodePoints() {
        var input = new CreateRoleDTO();
        input.setName("😀".repeat(80));
        input.setPermissionCodes(List.of("users.view"));
        input.setReviewToken("test-reference");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            // 八十个补充平面字符合法，第八十一个必须由同一父字段校验拒绝。
            var validator = factory.getValidator();
            assertTrue(validator.validate(input).isEmpty());
            input.setName("😀".repeat(81));
            assertTrue(validator.validate(input).stream()
                    .anyMatch(error -> error.getPropertyPath().toString().equals("name")));
        }
    }

    /**
     * 核对不包含秘密字段，但防重指纹必须包含密码的实际完整内容。
     */
    @Test
    void reviewOmitsSecretWhileCommandBindsIt() throws IOException {
        Path key = temporary.resolve("key.txt");
        Files.writeString(key, "test-only-hmac-material-at-least-thirty-two-bytes");

        var json = new JsonCodec(JsonMapper.builder().build());

        var fingerprints = new Fingerprints(json, new SecretKeyProvider(key.toString()));

        var input = new CreateUserDTO();
        input.setUsername("development");
        input.setDisplayName("开发人员");
        input.setInitialPassword("FirstTest123");

        String review = fingerprints.proposal(input);

        byte[] first = fingerprints.command("users.create", "create", input);
        input.setInitialPassword("SecondTest123");
        assertEquals(review, fingerprints.proposal(input));
        assertFalse(Arrays.equals(first, fingerprints.command("users.create", "create", input)));
        assertFalse(input.toString().contains("SecondTest123"));
    }

    /**
     * 百分号、下划线和转义符在普通查询中只能作为字面文本。
     */
    @Test
    void queryPatternsEscapeLiteralWildcards() {
        assertEquals("%!%!_!!%", TextSearch.pattern("%_!"));
        assertEquals(List.of("%姓名%", "%login%"), TextSearch.terms(" 姓名  LOGIN "));
        assertNotEquals(TextSearch.key("Ａｄｍｉｎ"), TextSearch.key("different"));
        assertEquals("admin", TextSearch.key(" Ａｄｍｉｎ "));
    }
}
