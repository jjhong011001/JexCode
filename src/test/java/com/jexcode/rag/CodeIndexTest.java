package com.jexcode.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {

    private static final EmbeddingClient TEST_EMBEDDINGS = new EmbeddingClient(
            "test", "test", "http://localhost", "") {
        @Override
        public float[] embed(String text) {
            return new float[]{1.0f, 0.0f};
        }
    };

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex(TEST_EMBEDDINGS);
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        System.setProperty("jexcode.rag.dir", "/tmp/jexcode-test-rag-index");
        CodeIndex indexer = new CodeIndex(TEST_EMBEDDINGS);
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(TEST_EMBEDDINGS, messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }
}
