package com.dataocean.module.knowledge.client.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.po.RagIndexBuild;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PythonRagBuildContractTest {
    @Configuration @EnableRetry(proxyTargetClass=true)
    static class Config {
        @Bean RestClient.Builder builder(){ return RestClient.builder().baseUrl("http://rag.test"); }
        @Bean MockRestServiceServer server(RestClient.Builder builder){ return MockRestServiceServer.bindTo(builder).build(); }
        @Bean PythonRagClient client(RestClient.Builder builder, MockRestServiceServer server){ return new PythonRagClientImpl(builder.build()); }
    }
    private RagIndexBuild build(){ return RagIndexBuild.builder().buildId("113260d5-c934-45a0-9e82-c615f35693ff").datasourceId(1L).sourceSnapshotId(1L).build(); }
    @Test void buildChunksUseManifestMemberContractAndExactBuildIdentity(){
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            var server=context.getBean(MockRestServiceServer.class);
            server.expect(requestTo("http://rag.test/internal/rag/builds/chunk"))
                    .andExpect(jsonPath("$.buildId").value(build().getBuildId()))
                    .andExpect(jsonPath("$.metadataSnapshotId").value(1))
                    .andRespond(withSuccess("{\"chunks\":[]}",MediaType.APPLICATION_JSON));
            assertThat(context.getBean(PythonRagClient.class).chunkBuildDocument(build(),13L,1,"# 主题")).isEmpty();
            server.verify();
        }
    }
    @Test void validationFailurePreservesBusinessReasonThroughRealRetryProxyWithoutRetry(){
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            var server=context.getBean(MockRestServiceServer.class);
            server.expect(requestTo("http://rag.test/internal/rag/builds/chunk"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThatThrownBy(()->context.getBean(PythonRagClient.class).chunkBuildDocument(build(),13L,1,"broken"))
                    .isInstanceOf(BusinessException.class).hasMessage("RAG build chunk 校验失败");
            server.verify();
        }
    }
}
