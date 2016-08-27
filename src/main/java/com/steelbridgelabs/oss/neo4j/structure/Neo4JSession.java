/*
 *  Copyright 2016 SteelBridge Laboratories, LLC.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  For more information: http://steelbridgelabs.com
 */

package com.steelbridgelabs.oss.neo4j.structure;

import org.apache.commons.lang.StringUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Statement;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.Values;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.summary.InputPosition;
import org.neo4j.driver.v1.summary.Notification;
import org.neo4j.driver.v1.summary.ProfiledPlan;
import org.neo4j.driver.v1.summary.ResultSummary;
import org.neo4j.driver.v1.types.Node;
import org.neo4j.driver.v1.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @author Rogelio J. Baucells
 */
class Neo4JSession implements AutoCloseable {

    private static class ProfileInformation {

        private static class ProfileInformationDetails {

            private int indentationLevel;
            private String operator;
            private String estimatedRows;
            private String rows;
            private String dbHits;
            private String variables;
        }

        private static final String OperatorColumnName = "Operator";
        private static final String EstimatedRowsColumnName = "Estimated Rows";
        private static final String RowsColumnName = "Rows";
        private static final String DBHitsColumnName = "DB Hits";
        private static final String VariablesColumnName = "Variables";

        private final List<ProfileInformationDetails> details = new LinkedList<>();

        private int operatorLength = OperatorColumnName.length();
        private int estimatedRowsLength = EstimatedRowsColumnName.length();
        private int rowsLength = RowsColumnName.length();
        private int dbHitsLength = DBHitsColumnName.length();
        private int variablesLength = VariablesColumnName.length();

        public void process(ProfiledPlan profilePlan) {
            Objects.requireNonNull(profilePlan, "profilePlan cannot be null");
            // process plan
            process(profilePlan, 0);
        }

        private void process(ProfiledPlan profilePlan, int indentationLevel) {
            // create details instance
            ProfileInformationDetails information = new ProfileInformationDetails();
            // operator
            information.operator = printOperator(profilePlan.operatorType(), indentationLevel);
            // arguments
            Map<String, Value> arguments = profilePlan.arguments();
            // compile information
            information.indentationLevel = indentationLevel;
            information.estimatedRows = printEstimatedRows(arguments.get("EstimatedRows"));
            information.rows = String.format(Locale.US, "%d", profilePlan.records());
            information.dbHits = String.format(Locale.US, "%d", profilePlan.dbHits());
            information.variables = profilePlan.identifiers().stream().map(String::trim).collect(Collectors.joining(", "));
            // append to list
            add(information);
            // children
            List<ProfiledPlan> children = profilePlan.children();
            // process children (backwards)
            for (int i = children.size() - 1; i >= 0; i--) {
                // current child
                ProfiledPlan child = children.get(i);
                // process child
                process(child, indentationLevel + i);
            }
        }

        private void add(ProfileInformationDetails information) {
            // update statistics
            operatorLength = information.operator.length() - 2 > operatorLength ? information.operator.length() - 2 : operatorLength;
            estimatedRowsLength = information.estimatedRows.length() > estimatedRowsLength ? information.estimatedRows.length() : estimatedRowsLength;
            rowsLength = information.rows.length() > rowsLength ? information.rows.length() : rowsLength;
            dbHitsLength = information.dbHits.length() > dbHitsLength ? information.dbHits.length() : dbHitsLength;
            variablesLength = information.variables.length() > variablesLength ? information.variables.length() : variablesLength;
            // append to list
            details.add(information);
        }

        private static String printOperator(String operator, int indentationLevel) {
            // create builder
            StringBuilder builder = new StringBuilder();
            // process indentation level
            for (int i = 0; i <= indentationLevel; i++)
                builder.append("| ");
            // append operator
            builder.append("+").append(operator);
            // return text
            return builder.toString();
        }

        private static String printEstimatedRows(Value estimatedRows) {
            // format number
            return estimatedRows != null ? String.format(Locale.US, "%d", (long)estimatedRows.asDouble()) : "";
        }

        @Override
        public String toString() {
            // create string builder
            StringBuilder builder = new StringBuilder("\n");
            // header
            builder.append("+-")
                .append(StringUtils.repeat("-", operatorLength)).append("-+-")
                .append(StringUtils.repeat("-", estimatedRowsLength)).append("-+-")
                .append(StringUtils.repeat("-", rowsLength)).append("-+-")
                .append(StringUtils.repeat("-", dbHitsLength)).append("-+-")
                .append(StringUtils.repeat("-", variablesLength)).append("-+\n");
            builder.append("| ")
                .append(OperatorColumnName).append(StringUtils.repeat(" ", operatorLength - OperatorColumnName.length())).append(" + ")
                .append(EstimatedRowsColumnName).append(StringUtils.repeat(" ", estimatedRowsLength - EstimatedRowsColumnName.length())).append(" + ")
                .append(RowsColumnName).append(StringUtils.repeat(" ", rowsLength - RowsColumnName.length())).append(" + ")
                .append(DBHitsColumnName).append(StringUtils.repeat(" ", dbHitsLength - DBHitsColumnName.length())).append(" + ")
                .append(VariablesColumnName).append(StringUtils.repeat(" ", variablesLength - VariablesColumnName.length())).append(" |\n");
            builder.append("+-")
                .append(StringUtils.repeat("-", operatorLength)).append("-+-")
                .append(StringUtils.repeat("-", estimatedRowsLength)).append("-+-")
                .append(StringUtils.repeat("-", rowsLength)).append("-+-")
                .append(StringUtils.repeat("-", dbHitsLength)).append("-+-")
                .append(StringUtils.repeat("-", variablesLength)).append("-+\n");
            // running state
            boolean first = true;
            int lastIndentationLevel = -1;
            // loop details
            for (ProfileInformationDetails item : details) {
                // append line separator if needed
                if (!first) {
                    // check indentation level changed
                    if (lastIndentationLevel < item.indentationLevel) {
                        // process indentation level
                        for (int i = 0; i < item.indentationLevel; i++)
                            builder.append("| ");
                        // append last level
                        builder.append("|\\  ");
                    }
                    else {
                        // process indentation level
                        for (int i = 0; i <= item.indentationLevel; i++)
                            builder.append("| ");
                        // append last level
                        builder.append("| ");
                    }
                    // operator
                    builder.append(StringUtils.repeat(" ", operatorLength - item.indentationLevel * 2 - 2)).append(" +-");
                    // estimated rows
                    builder.append(StringUtils.repeat("-", estimatedRowsLength)).append("-+-");
                    // rows
                    builder.append(StringUtils.repeat("-", rowsLength)).append("-+-");
                    // db hits
                    builder.append(StringUtils.repeat("-", dbHitsLength)).append("-+-");
                    // variables
                    builder.append(StringUtils.repeat("-", variablesLength)).append("-+");
                    // end of header
                    builder.append("\n");
                }
                // operator
                builder.append(item.operator).append(StringUtils.repeat(" ", operatorLength - item.operator.length() + 2)).append(" |");
                // estimated rows
                builder.append(" ").append(StringUtils.repeat(" ", estimatedRowsLength - item.estimatedRows.length())).append(item.estimatedRows).append(" |");
                // rows
                builder.append(" ").append(StringUtils.repeat(" ", rowsLength - item.rows.length())).append(item.rows).append(" |");
                // db hits
                builder.append(" ").append(StringUtils.repeat(" ", dbHitsLength - item.dbHits.length())).append(item.dbHits).append(" |");
                // variables
                builder.append(" ").append(item.variables).append(StringUtils.repeat(" ", variablesLength - item.variables.length())).append(" |");
                // close row
                builder.append("\n");
                // update running state
                first = false;
                lastIndentationLevel = item.indentationLevel;
            }
            // footer
            builder.append("+-")
                .append(StringUtils.repeat("-", operatorLength)).append("-+-")
                .append(StringUtils.repeat("-", estimatedRowsLength)).append("-+-")
                .append(StringUtils.repeat("-", rowsLength)).append("-+-")
                .append(StringUtils.repeat("-", dbHitsLength)).append("-+-")
                .append(StringUtils.repeat("-", variablesLength)).append("-+\n");
            // return table
            return builder.toString();
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(Neo4JSession.class);

    private final Neo4JGraph graph;
    private final Neo4JReadPartition partition;
    private final Session session;
    private final Neo4JElementIdProvider<?> vertexIdProvider;
    private final Neo4JElementIdProvider<?> edgeIdProvider;

    private final Map<Object, Neo4JVertex> vertices = new HashMap<>();
    private final Map<Object, Neo4JEdge> edges = new HashMap<>();
    private final Set<Object> deletedVertices = new HashSet<>();
    private final Set<Object> deletedEdges = new HashSet<>();
    private final Set<Neo4JVertex> transientVertices = new HashSet<>();
    private final Set<Neo4JEdge> transientEdges = new HashSet<>();
    private final Set<Neo4JVertex> vertexUpdateQueue = new HashSet<>();
    private final Set<Neo4JEdge> edgeUpdateQueue = new HashSet<>();
    private final Set<Neo4JVertex> vertexDeleteQueue = new HashSet<>();
    private final Set<Neo4JEdge> edgeDeleteQueue = new HashSet<>();
    private final String vertexIdFieldName;
    private final String edgeIdFieldName;

    private org.neo4j.driver.v1.Transaction transaction;
    private boolean verticesLoaded = false;
    private boolean edgesLoaded = false;
    private boolean profilerEnabled = false;

    public Neo4JSession(Neo4JGraph graph, Session session, Neo4JElementIdProvider<?> vertexIdProvider, Neo4JElementIdProvider<?> edgeIdProvider) {
        Objects.requireNonNull(graph, "graph cannot be null");
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(vertexIdProvider, "vertexIdProvider cannot be null");
        Objects.requireNonNull(edgeIdProvider, "edgeIdProvider cannot be null");
        // log information
        if (logger.isDebugEnabled())
            logger.debug("Creating session [{}]", session.hashCode());
        // store fields
        this.graph = graph;
        this.partition = graph.getPartition();
        this.session = session;
        this.vertexIdProvider = vertexIdProvider;
        this.edgeIdProvider = edgeIdProvider;
        // initialize field ids names
        vertexIdFieldName = vertexIdProvider.idFieldName();
        edgeIdFieldName = edgeIdProvider.idFieldName();
    }

    public org.neo4j.driver.v1.Transaction beginTransaction() {
        // check we have a transaction already in progress
        if (transaction != null && transaction.isOpen())
            throw Transaction.Exceptions.transactionAlreadyOpen();
        // begin transaction
        transaction = session.beginTransaction();
        // log information
        if (logger.isDebugEnabled())
            logger.debug("Beginning transaction on session [{}]-[{}]", session.hashCode(), transaction.hashCode());
        // return transaction instance
        return transaction;
    }

    public boolean isTransactionOpen() {
        return transaction != null && transaction.isOpen();
    }

    public void commit() {
        // check we have an open transaction
        if (transaction != null) {
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Committing transaction [{}]", transaction.hashCode());
            // indicate success
            transaction.success();
            // flush session
            flush();
            // close neo4j transaction (this is the moment that data is committed to the server)
            transaction.close();
            // commit transient vertices
            transientVertices.forEach(Neo4JVertex::commit);
            // commit transient edges
            transientEdges.forEach(Neo4JEdge::commit);
            // commit dirty vertices
            vertexUpdateQueue.forEach(Neo4JVertex::commit);
            // commit dirty edges
            edgeUpdateQueue.forEach(Neo4JEdge::commit);
            // clean internal structures
            deletedEdges.clear();
            edgeDeleteQueue.clear();
            deletedVertices.clear();
            vertexDeleteQueue.clear();
            transientEdges.clear();
            transientVertices.clear();
            vertexUpdateQueue.clear();
            edgeUpdateQueue.clear();
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Successfully committed transaction [{}]", transaction.hashCode());
            // remove instance
            transaction = null;
        }
    }

    public void rollback() {
        // check we have an open transaction
        if (transaction != null) {
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Rolling back transaction [{}]", transaction.hashCode());
            // indicate failure
            transaction.failure();
            // close neo4j transaction (this is the moment that data is rolled-back from the server)
            transaction.close();
            // reset vertices loaded flag if needed
            if (!vertexUpdateQueue.isEmpty() || !deletedVertices.isEmpty())
                verticesLoaded = false;
            // reset edges loaded flag if needed
            if (!edgeUpdateQueue.isEmpty() || !deletedEdges.isEmpty())
                edgesLoaded = false;
            // remove transient vertices from map
            transientVertices.forEach(vertex -> vertices.remove(vertex.id()));
            // remove transient edges from map
            transientEdges.forEach(edge -> edges.remove(edge.id()));
            // rollback dirty vertices
            vertexUpdateQueue.forEach(Neo4JVertex::rollback);
            // rollback dirty edges
            edgeUpdateQueue.forEach(Neo4JEdge::rollback);
            // restore deleted vertices
            vertexDeleteQueue.forEach(vertex -> {
                // restore in map
                vertices.put(vertex.id(), vertex);
                // rollback vertex
                vertex.rollback();
            });
            // restore deleted edges
            edgeDeleteQueue.forEach(edge -> {
                // restore in map
                edges.put(edge.id(), edge);
                // rollback edge
                edge.rollback();
            });
            // clean internal structures
            deletedEdges.clear();
            edgeDeleteQueue.clear();
            deletedVertices.clear();
            vertexDeleteQueue.clear();
            transientEdges.clear();
            transientVertices.clear();
            vertexUpdateQueue.clear();
            edgeUpdateQueue.clear();
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Successfully rolled-back transaction [{}]", transaction.hashCode());
            // remove instance
            transaction = null;
        }
    }

    public void closeTransaction() {
        // check we have an open transaction
        if (transaction != null) {
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Closing transaction [{}]", transaction.hashCode());
            // close transaction
            transaction.close();
            // remove instance
            transaction = null;
        }
    }

    public Neo4JVertex addVertex(Object... keyValues) {
        Objects.requireNonNull(keyValues, "keyValues cannot be null");
        // verify parameters are key/value pairs
        ElementHelper.legalPropertyKeyValueArray(keyValues);
        // id cannot be present
        if (ElementHelper.getIdValue(keyValues).isPresent())
            throw Vertex.Exceptions.userSuppliedIdsNotSupported();
        // create vertex
        Neo4JVertex vertex = new Neo4JVertex(graph, this, vertexIdProvider, vertexIdProvider.generateId(), Arrays.asList(ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL).split(Neo4JVertex.LabelDelimiter)));
        // add vertex to transient set (before processing properties to avoid having a transient vertex in update queue)
        transientVertices.add(vertex);
        // attach properties
        ElementHelper.attachProperties(vertex, keyValues);
        // register element
        registerVertex(vertex);
        // return vertex
        return vertex;
    }

    Neo4JEdge addEdge(String label, Neo4JVertex out, Neo4JVertex in, Object... keyValues) {
        Objects.requireNonNull(label, "label cannot be null");
        Objects.requireNonNull(out, "out cannot be null");
        Objects.requireNonNull(in, "in cannot be null");
        Objects.requireNonNull(keyValues, "keyValues cannot be null");
        // validate label
        ElementHelper.validateLabel(label);
        // verify parameters are key/value pairs
        ElementHelper.legalPropertyKeyValueArray(keyValues);
        // id cannot be present
        if (ElementHelper.getIdValue(keyValues).isPresent())
            throw Vertex.Exceptions.userSuppliedIdsNotSupported();
        // create edge
        Neo4JEdge edge = new Neo4JEdge(graph, this, edgeIdProvider, edgeIdProvider.generateId(), label, out, in);
        // register transient edge (before processing properties to avoid having a transient edge in update queue)
        transientEdges.add(edge);
        // attach properties
        ElementHelper.attachProperties(edge, keyValues);
        // register element
        registerEdge(edge);
        // register transient edge with adjacent vertices
        out.addOutEdge(edge);
        in.addInEdge(edge);
        // return edge
        return edge;
    }

    private String generateVertexMatchPattern(String alias) {
        // get labels from read partition to be applied in vertex patterns
        Set<String> labels = partition.vertexMatchPatternLabels();
        if (!labels.isEmpty()) {
            // vertex match within partition
            return "(" + alias + labels.stream().map(label -> ":`" + label + "`").collect(Collectors.joining("")) + ")";
        }
        // vertex match
        return "(" + alias + ")";
    }

    boolean isProfilerEnabled() {
        return profilerEnabled;
    }

    void setProfilerEnabled(boolean profilerEnabled) {
        this.profilerEnabled = profilerEnabled;
    }

    public Iterator<Vertex> vertices(Object[] ids) {
        Objects.requireNonNull(ids, "ids cannot be null");
        // verify identifiers
        verifyIdentifiers(Vertex.class, ids);
        // check we have all vertices already loaded
        if (!verticesLoaded) {
            // check ids
            if (ids.length > 0) {
                // parameters as a stream
                Set<Object> identifiers = Arrays.stream(ids).map(id -> processIdentifier(vertexIdProvider, id)).collect(Collectors.toSet());
                // filter ids, remove ids already in memory (only ids that might exist on server)
                List<Object> filter = identifiers.stream().filter(id -> !vertices.containsKey(id)).collect(Collectors.toList());
                // check we need to execute statement in server
                if (!filter.isEmpty()) {
                    // vertex match predicate
                    String predicate = partition.vertexMatchPredicate("n");
                    // cypher statement
                    Statement statement = new Statement("MATCH " + generateVertexMatchPattern("n") + " WHERE n." + vertexIdFieldName + " in {ids}" + (predicate != null ? " AND " + predicate : "") + " RETURN n", Values.parameters("ids", filter));
                    // execute statement
                    StatementResult result = executeStatement(statement);
                    // create stream from query
                    Stream<Vertex> query = vertices(result);
                    // combine stream from memory and query result
                    Iterator<Vertex> iterator = combine(identifiers.stream().filter(vertices::containsKey).map(id -> (Vertex)vertices.get(id)), query);
                    // process summary (query has been already consumed by combine)
                    processResultSummary(result.consume());
                    // return iterator
                    return iterator;
                }
                // no need to execute query, only items in memory
                return combine(identifiers.stream().filter(vertices::containsKey).map(id -> (Vertex)vertices.get(id)), Stream.empty());
            }
            // vertex match predicate
            String predicate = partition.vertexMatchPredicate("n");
            // cypher statement for all vertices
            Statement statement = new Statement("MATCH " + generateVertexMatchPattern("n") + (predicate != null ? " WHERE " + predicate : "") + " RETURN n");
            // execute statement
            StatementResult result = executeStatement(statement);
            // create stream from query
            Stream<Vertex> query = vertices(result);
            // combine stream from memory (transient) and query result
            Iterator<Vertex> iterator = combine(transientVertices.stream().map(vertex -> (Vertex)vertex), query);
            // process summary (query has been already consumed by combine)
            processResultSummary(result.consume());
            // it is safe to update loaded flag at this time
            verticesLoaded = true;
            // return iterator
            return iterator;
        }
        // check ids
        if (ids.length > 0) {
            // parameters as a stream (set to remove duplicated ids)
            Set<Object> identifiers = Arrays.stream(ids).map(id -> processIdentifier(vertexIdProvider, id)).collect(Collectors.toSet());
            // no need to execute query, only items in memory
            return identifiers.stream()
                .filter(vertices::containsKey)
                .map(id -> (Vertex)vertices.get(id))
                .collect(Collectors.toCollection(LinkedList::new))
                .iterator();
        }
        // no need to execute query, only items in memory
        return vertices.values().stream()
            .map(vertex -> (Vertex)vertex)
            .collect(Collectors.toCollection(LinkedList::new))
            .iterator();
    }

    Stream<Vertex> vertices(StatementResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        // create stream from result, skip deleted vertices
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(result, Spliterator.NONNULL | Spliterator.IMMUTABLE), false)
            .map(this::loadVertex)
            .filter(vertex -> vertex != null);
    }

    public Iterator<Edge> edges(Object[] ids) {
        Objects.requireNonNull(ids, "ids cannot be null");
        // verify identifiers
        verifyIdentifiers(Edge.class, ids);
        // check we have all edges already loaded
        if (!edgesLoaded) {
            // check ids
            if (ids.length > 0) {
                // parameters as a stream
                Set<Object> identifiers = Arrays.stream(ids).map(id -> processIdentifier(edgeIdProvider, id)).collect(Collectors.toSet());
                // filter ids, remove ids already in memory (only ids that might exist on server)
                List<Object> filter = identifiers.stream().filter(id -> !edges.containsKey(id)).collect(Collectors.toList());
                // check we need to execute statement in server
                if (!filter.isEmpty()) {
                    // vertex match predicates
                    String outVertexPredicate = partition.vertexMatchPredicate("n");
                    String inVertexPredicate = partition.vertexMatchPredicate("m");
                    // cypher statement
                    Statement statement = new Statement("MATCH " + generateVertexMatchPattern("n") + "-[r]->" + generateVertexMatchPattern("m") + " WHERE r." + edgeIdFieldName + " in {ids}" + (outVertexPredicate != null && inVertexPredicate != null ? " AND " + outVertexPredicate + " AND " + inVertexPredicate : "") + " RETURN n, r, m", Values.parameters("ids", filter));
                    // execute statement
                    StatementResult result = executeStatement(statement);
                    // find edges
                    Stream<Edge> query = edges(result);
                    // combine stream from memory and query result
                    Iterator<Edge> iterator = combine(identifiers.stream().filter(edges::containsKey).map(id -> (Edge)edges.get(id)), query);
                    // process summary (query has been already consumed by combine)
                    processResultSummary(result.consume());
                    // return iterator
                    return iterator;
                }
                // no need to execute query, only items in memory
                return combine(identifiers.stream().filter(edges::containsKey).map(id -> (Edge)edges.get(id)), Stream.empty());
            }
            // vertex match predicates
            String outVertexPredicate = partition.vertexMatchPredicate("n");
            String inVertexPredicate = partition.vertexMatchPredicate("m");
            // cypher statement for all edges in database
            Statement statement = new Statement("MATCH " + generateVertexMatchPattern("n") + "-[r]->" + generateVertexMatchPattern("m") + (outVertexPredicate != null && inVertexPredicate != null ? " WHERE " + outVertexPredicate + " AND " + inVertexPredicate : "") + " RETURN n, r, m");
            // execute statement
            StatementResult result = executeStatement(statement);
            // find edges
            Stream<Edge> query = edges(result);
            // combine stream from memory (transient) and query result
            Iterator<Edge> iterator = combine(transientEdges.stream().map(edge -> (Edge)edge), query);
            // process summary (query has been already consumed by combine)
            processResultSummary(result.consume());
            // it is safe to update loaded flag at this time
            edgesLoaded = true;
            // return iterator
            return iterator;
        }
        // check ids
        if (ids.length > 0) {
            // parameters as a stream (set to remove duplicated ids)
            Set<Object> identifiers = Arrays.stream(ids).map(id -> processIdentifier(edgeIdProvider, id)).collect(Collectors.toSet());
            // no need to execute query, only items in memory
            return identifiers.stream()
                .filter(edges::containsKey)
                .map(id -> (Edge)edges.get(id))
                .collect(Collectors.toCollection(LinkedList::new))
                .iterator();
        }
        // no need to execute query, only items in memory
        return edges.values().stream()
            .map(edge -> (Edge)edge)
            .collect(Collectors.toCollection(LinkedList::new))
            .iterator();
    }

    Stream<Edge> edges(StatementResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        // create stream from result, skip deleted edges
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(result, Spliterator.NONNULL | Spliterator.IMMUTABLE), false)
            .map(this::loadEdge)
            .filter(edge -> edge != null);
    }

    private static <T> Iterator<T> combine(Stream<T> collection, Stream<T> query) {
        // create a copy of first stream (state can be modified in the middle of the iteration)
        List<T> copy = collection.collect(Collectors.toCollection(LinkedList::new));
        // iterate query and accumulate to list
        query.forEach(copy::add);
        // return iterator
        return copy.iterator();
    }

    void removeEdge(Neo4JEdge edge, boolean explicit) {
        // edge id
        Object id = edge.id();
        // check edge is transient
        if (transientEdges.contains(edge)) {
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Deleting transient edge: {}", edge);
            // check explicit delete on edge
            if (explicit) {
                // remove references from adjacent vertices
                edge.vertices(Direction.BOTH).forEachRemaining(vertex -> {
                    // remove from vertex
                    ((Neo4JVertex)vertex).removeEdge(edge);
                });
            }
            // remove it from transient set
            transientEdges.remove(edge);
        }
        else {
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Deleting edge: {}", edge);
            // mark edge as deleted (prevent returning edge in query results)
            deletedEdges.add(id);
            // check we need to execute delete statement on edge
            if (explicit) {
                // remove references from adjacent vertices
                edge.vertices(Direction.BOTH).forEachRemaining(vertex -> {
                    // remove from vertex
                    ((Neo4JVertex)vertex).removeEdge(edge);
                });
                // add to delete queue
                edgeDeleteQueue.add(edge);
            }
            // remove it from update queue (avoid issuing MERGE command for an element that has been deleted)
            edgeUpdateQueue.remove(edge);
        }
        // remove edge from map
        edges.remove(id);
    }

    private static <T> void verifyIdentifiers(Class<T> elementClass, Object... ids) {
        // check length
        if (ids.length > 0) {
            // first element in array
            Object first = ids[0];
            // first element class
            Class<?> firstClass = first.getClass();
            // check it is an element
            if (elementClass.isAssignableFrom(firstClass)) {
                // all ids must be of the same class
                if (!Stream.of(ids).allMatch(id -> elementClass.isAssignableFrom(id.getClass())))
                    throw Graph.Exceptions.idArgsMustBeEitherIdOrElement();
            }
            else if (!Stream.of(ids).map(Object::getClass).allMatch(firstClass::equals))
                throw Graph.Exceptions.idArgsMustBeEitherIdOrElement();
        }
    }

    private static Object processIdentifier(Neo4JElementIdProvider provider, Object id) {
        // vertex
        if (id instanceof Vertex)
            return ((Vertex)id).id();
        // edge
        if (id instanceof Edge)
            return ((Edge)id).id();
        // delegate processing to provider
        return provider.processIdentifier(id);
    }

    private Vertex loadVertex(Record record) {
        // node
        Node node = record.get(0).asNode();
        // vertex id
        Object vertexId = node.get(vertexIdFieldName).asObject();
        // check vertex has been deleted
        if (!deletedVertices.contains(vertexId)) {
            // check this vertex has been already loaded into this session
            Vertex vertex = vertices.get(vertexId);
            if (vertex == null) {
                // check node belongs to partition
                if (partition.containsVertex(StreamSupport.stream(node.labels().spliterator(), false).collect(Collectors.toSet()))) {
                    // create and register vertex
                    return registerVertex(new Neo4JVertex(graph, this, vertexIdProvider, node));
                }
                // skip vertex (not in partition)
                return null;
            }
            // return vertex
            return vertex;
        }
        // skip vertex (deleted)
        return null;
    }

    private Edge loadEdge(Record record) {
        // relationship
        Relationship relationship = record.get(1).asRelationship();
        // edge id
        Object edgeId = relationship.get(edgeIdFieldName).asObject();
        // check edge has been deleted
        if (!deletedEdges.contains(edgeId)) {
            // check we have record in memory
            Neo4JEdge edge = edges.get(edgeId);
            if (edge == null) {
                // nodes
                Node firstNode = record.get(0).asNode();
                Node secondNode = record.get(2).asNode();
                // node ids
                Object firstNodeId = firstNode.get(vertexIdFieldName).asObject();
                Object secondNodeId = secondNode.get(vertexIdFieldName).asObject();
                // check edge has been deleted (one of the vertices was deleted) or the vertices are not in the read partition
                if (deletedVertices.contains(firstNodeId) || deletedVertices.contains(secondNodeId) || !partition.containsVertex(StreamSupport.stream(firstNode.labels().spliterator(), false).collect(Collectors.toSet())) || !partition.containsVertex(StreamSupport.stream(secondNode.labels().spliterator(), false).collect(Collectors.toSet())))
                    return null;
                // check we have first vertex in memory
                Neo4JVertex firstVertex = vertices.get(firstNodeId);
                if (firstVertex == null) {
                    // create vertex
                    firstVertex = new Neo4JVertex(graph, this, vertexIdProvider, firstNode);
                    // register it
                    registerVertex(firstVertex);
                }
                // check we have second vertex in memory
                Neo4JVertex secondVertex = vertices.get(secondNodeId);
                if (secondVertex == null) {
                    // create vertex
                    secondVertex = new Neo4JVertex(graph, this, vertexIdProvider, secondNode);
                    // register it
                    registerVertex(secondVertex);
                }
                // find out start and end of the relationship (edge could come in either direction)
                Neo4JVertex out = relationship.startNodeId() == firstNode.id() ? firstVertex : secondVertex;
                Neo4JVertex in = relationship.endNodeId() == firstNode.id() ? firstVertex : secondVertex;
                // create edge
                edge = new Neo4JEdge(graph, this, edgeIdProvider, out, relationship, in);
                // register with adjacent vertices
                out.addOutEdge(edge);
                in.addInEdge(edge);
                // register edge
                return registerEdge(edge);
            }
            // return edge
            return edge;
        }
        // skip edge
        return null;
    }

    private Vertex registerVertex(Neo4JVertex vertex) {
        // map vertex
        vertices.put(vertex.id(), vertex);
        // return vertex
        return vertex;
    }

    private Edge registerEdge(Neo4JEdge edge) {
        // edge id
        Object id = edge.id();
        // map edge
        edges.put(id, edge);
        // return vertex
        return edge;
    }

    void removeVertex(Neo4JVertex vertex) {
        // vertex id
        Object id = vertex.id();
        // check vertex is transient
        if (transientVertices.contains(vertex)) {
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Deleting transient vertex: {}", vertex);
            // remove it from transient set
            transientVertices.remove(vertex);
        }
        else {
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Deleting vertex: {}", vertex);
            // mark vertex as deleted (prevent returning vertex in query results)
            deletedVertices.add(id);
            // add vertex to queue
            vertexDeleteQueue.add(vertex);
            // remove it from update queue (avoid issuing MERGE command for an element that has been deleted)
            vertexUpdateQueue.remove(vertex);
        }
        // remove vertex from map
        vertices.remove(id);
    }

    void dirtyVertex(Neo4JVertex vertex) {
        // check element is a transient one
        if (!transientVertices.contains(vertex)) {
            // add vertex to processing queue
            vertexUpdateQueue.add(vertex);
        }
    }

    void dirtyEdge(Neo4JEdge edge) {
        // check element is a transient one
        if (!transientEdges.contains(edge)) {
            // add edge to processing queue
            edgeUpdateQueue.add(edge);
        }
    }

    private void flush() {
        try {
            // delete edges
            deleteEdges();
            // delete vertices
            deleteVertices();
            // create vertices
            createVertices();
            // create edges
            createEdges();
            // update edges
            updateEdges();
            // update vertices (after edges to be able to locate the vertex if referenced by an edge)
            updateVertices();
        }
        catch (ClientException ex) {
            // log error
            if (logger.isErrorEnabled())
                logger.error("Error committing transaction [{}]", transaction.hashCode(), ex);
            // throw original exception
            throw ex;
        }
    }

    private void createVertices() {
        // insert vertices
        for (Neo4JVertex vertex : transientVertices) {
            // create statement
            Statement statement = vertex.insertStatement();
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Executing Cypher statement on transaction [{}]: {}", transaction.hashCode(), statement.toString());
            // execute statement
            transaction.run(statement);
        }
    }

    private void updateVertices() {
        // update vertices
        for (Neo4JVertex vertex : vertexUpdateQueue) {
            // create statement
            Statement statement = vertex.updateStatement();
            if (statement != null) {
                // log information
                if (logger.isDebugEnabled())
                    logger.debug("Executing Cypher statement on transaction [{}]: {}", transaction.hashCode(), statement.toString());
                // execute statement
                transaction.run(statement);
            }
        }
    }

    private void deleteVertices() {
        // delete vertices
        for (Neo4JVertex vertex : vertexDeleteQueue) {
            // create statement
            Statement statement = vertex.deleteStatement();
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Executing Cypher statement on transaction [{}]: {}", transaction.hashCode(), statement.toString());
            // execute statement
            transaction.run(statement);
        }
    }

    private void createEdges() {
        // insert edges
        for (Neo4JEdge edge : transientEdges) {
            // create statement
            Statement statement = edge.insertStatement();
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Executing Cypher statement on transaction [{}]: {}", transaction.hashCode(), statement.toString());
            // execute statement
            transaction.run(statement);
        }
    }

    private void updateEdges() {
        // update edges
        for (Neo4JEdge edge : edgeUpdateQueue) {
            // create statement
            Statement statement = edge.updateStatement();
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Executing Cypher statement on transaction [{}]: {}", transaction.hashCode(), statement.toString());
            // execute statement
            transaction.run(statement);
        }
    }

    private void deleteEdges() {
        // delete edges
        for (Neo4JEdge edge : edgeDeleteQueue) {
            // create statement
            Statement statement = edge.deleteStatement();
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Executing Cypher statement on transaction [{}]: {}", transaction.hashCode(), statement.toString());
            // execute statement
            transaction.run(statement);
        }
    }

    StatementResult executeStatement(Statement statement) {
        try {
            // statement to execute
            Statement cypherStatement = statement;
            // check we need to modify statement
            if (profilerEnabled && logger.isInfoEnabled()) {
                // statement text
                String text  = cypherStatement.text();
                if (text != null) {
                    // use upper case
                    text = text.toUpperCase(Locale.US);
                    // check we can append PROFILE to current statement
                    if (!text.startsWith("PROFILE") && !text.startsWith("EXPLAIN")) {
                        // create new statement
                        cypherStatement = new Statement("PROFILE " + statement.text(), statement.parameters());
                    }
                }
            }
            // log information
            if (logger.isDebugEnabled())
                logger.debug("Executing Cypher statement on transaction [{}]: {}", transaction.hashCode(), cypherStatement.toString());
            // execute on transaction
            return transaction.run(cypherStatement);
        }
        catch (ClientException ex) {
            // log error
            if (logger.isErrorEnabled())
                logger.error("Error executing Cypher statement on transaction [{}]", transaction.hashCode(), ex);
            // throw original exception
            throw ex;
        }
    }

    static void processResultSummary(ResultSummary summary) {
        Objects.requireNonNull(summary, "summary cannot be null");
        // log information
        if (logger.isInfoEnabled() && summary.hasProfile()) {
            // create builder
            StringBuilder builder = new StringBuilder();
            // append statement
            builder.append("Profile for CYPHER statement: ").append(summary.statement()).append("\n");
            // create profile information
            ProfileInformation profileInformation = new ProfileInformation();
            // process profile
            profileInformation.process(summary.profile());
            // log tabular results
            builder.append(profileInformation.toString());
            // log information
            logger.info(builder.toString());
        }
        // log notifications
        if (logger.isWarnEnabled()) {
            // loop notifications
            for (Notification notification : summary.notifications()) {
                // position if any
                InputPosition position = notification.position();
                // log information
                logger.warn("CYPHER statement [{}] notification; severity: {}, code: {}, title: {}, description: {}{}", summary.statement(), notification.severity(), notification.code(), notification.title(), notification.description(), position != null ? ", [line: " + position.line() + ", position: " + position.column() + ", offset: " + position.offset() + "]" : "");
            }
        }
    }

    public void close() {
        // close transaction
        closeTransaction();
        // log information
        if (logger.isDebugEnabled())
            logger.debug("Closing neo4j session [{}]", session.hashCode());
        // close session
        session.close();
    }

    @Override
    @SuppressWarnings("checkstyle:NoFinalizer")
    protected void finalize() throws Throwable {
        // check session is open
        if (session.isOpen()) {
            // log information
            if (logger.isErrorEnabled())
                logger.error("Finalizing Neo4JSession [{}] without explicit call to close(), the code is leaking sessions!", session.hashCode());
        }
        // base implementation
        super.finalize();
    }
}
