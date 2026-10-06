package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorSearchBatchTest {

    @Test
    void embedsAndWritesThirtyTwoAtATimeWithoutChangingInputOrder() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList())).thenAnswer(call -> {
            List<TextSegment> segments = call.getArgument(0);
            return Response.from(segments.stream().map(segment -> {
                int number = Integer.parseInt(segment.text().substring(5));
                return Embedding.from(new float[]{number + 1f, number + 2f});
            }).toList());
        });

        Connection first = mock(Connection.class);
        Connection second = mock(Connection.class);
        PreparedStatement firstStatement = mock(PreparedStatement.class);
        PreparedStatement secondStatement = mock(PreparedStatement.class);
        when(first.getAutoCommit()).thenReturn(true);
        when(second.getAutoCommit()).thenReturn(true);
        when(first.prepareStatement(anyString())).thenReturn(firstStatement);
        when(second.prepareStatement(anyString())).thenReturn(secondStatement);
        when(firstStatement.executeBatch()).thenReturn(IntStream.range(0, 32).map(i -> 1).toArray());
        when(secondStatement.executeBatch()).thenReturn(new int[]{1});
        AtomicBoolean firstCommitted = new AtomicBoolean();
        AtomicBoolean secondCommitted = new AtomicBoolean();
        doAnswer(call -> { firstCommitted.set(true); return null; }).when(first).commit();
        doAnswer(call -> { secondCommitted.set(true); return null; }).when(second).commit();

        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(first, second);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(source);
        VectorSearchService service = new VectorSearchService(model, mock(KbChunkMapper.class), jdbc);

        List<VectorSearchService.VectorInput> inputs = IntStream.range(0, 33)
                .mapToObj(i -> new VectorSearchService.VectorInput("id-" + i, "text-" + i)).toList();
        List<Integer> committedCounts = new ArrayList<>();
        service.storeVectors("collection", "document", "owner", inputs, count -> {
            committedCounts.add(count);
            if (committedCounts.size() == 1) assertTrue(firstCommitted.get());
            else assertTrue(secondCommitted.get());
        });
        assertEquals(List.of(32, 1), committedCounts);

        @SuppressWarnings("unchecked")
        var segments = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model, times(2)).embedAll(segments.capture());
        assertEquals(32, segments.getAllValues().get(0).size());
        assertEquals(1, segments.getAllValues().get(1).size());
        assertEquals("text-0", ((TextSegment) segments.getAllValues().get(0).get(0)).text());
        assertEquals("text-32", ((TextSegment) segments.getAllValues().get(1).get(0)).text());

        var firstIds = org.mockito.ArgumentCaptor.forClass(String.class);
        var firstVectors = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(firstStatement, times(32)).setString(eq(1), firstIds.capture());
        verify(firstStatement, times(32)).setString(eq(6), firstVectors.capture());
        for (int i = 0; i < 32; i++) {
            assertEquals("id-" + i, firstIds.getAllValues().get(i));
            assertEquals("[" + (i + 1f) + "," + (i + 2f) + "]", firstVectors.getAllValues().get(i));
        }
        verify(firstStatement, times(32)).addBatch();
        verify(firstStatement).executeBatch();
        verify(first).commit();
        verify(first, never()).rollback();
        verify(first).setAutoCommit(false);
        verify(first).setAutoCommit(true);

        verify(secondStatement).setString(1, "id-32");
        verify(secondStatement).setString(6, "[33.0,34.0]");
        verify(secondStatement).addBatch();
        verify(secondStatement).executeBatch();
        verify(second).commit();
        verify(second, never()).rollback();
    }

    @Test
    void incompleteOrInconsistentEmbeddingResponsesDoNotWriteAnything() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorSearchService service = new VectorSearchService(model, mock(KbChunkMapper.class), jdbc);
        List<VectorSearchService.VectorInput> inputs = List.of(
                new VectorSearchService.VectorInput("a", "first"),
                new VectorSearchService.VectorInput("b", "second"));

        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{1f, 2f}))));
        assertThrows(IllegalStateException.class, () -> service.storeVectors("c", "d", "u", inputs));
        verifyNoInteractions(jdbc);

        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(
                Embedding.from(new float[]{1f, 2f}), Embedding.from(new float[]{3f}))));
        assertThrows(IllegalStateException.class, () -> service.storeVectors("c", "d", "u", inputs));
        verifyNoInteractions(jdbc);

        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(
                Embedding.from(new float[]{1f, 2f}), Embedding.from(new float[]{Float.NaN, 4f}))));
        assertThrows(IllegalStateException.class, () -> service.storeVectors("c", "d", "u", inputs));
        verifyNoInteractions(jdbc);
    }

    @Test
    void sqlBatchFailureRollsBackBeforeReturningFailure() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(new float[]{1f, 2f}))));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenThrow(new SQLException("database unavailable"));
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(source);
        VectorSearchService service = new VectorSearchService(model, mock(KbChunkMapper.class), jdbc);

        AtomicInteger progressCalls = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> service.storeVectors("c", "d", "u", List.of(new VectorSearchService.VectorInput("a", "first")),
                        count -> progressCalls.incrementAndGet()));
        assertEquals(0, progressCalls.get());
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void existingVectorLookupOnlyReadsTheRequestedDocument() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("a", "b");
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(source);
        VectorSearchService service = new VectorSearchService(mock(EmbeddingModel.class), mock(KbChunkMapper.class), jdbc);

        assertEquals(Set.of("a", "b"), service.existingVectorChunkIds("document"));
        verify(statement).setString(1, "document");
        verify(statement, never()).executeUpdate();
    }
}
