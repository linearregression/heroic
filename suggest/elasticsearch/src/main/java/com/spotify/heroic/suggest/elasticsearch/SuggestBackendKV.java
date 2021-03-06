/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.suggest.elasticsearch;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.common.Grouped;
import com.spotify.heroic.common.Groups;
import com.spotify.heroic.common.OptionalLimit;
import com.spotify.heroic.common.RangeFilter;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.common.Statistics;
import com.spotify.heroic.elasticsearch.AbstractElasticsearchBackend;
import com.spotify.heroic.elasticsearch.BackendType;
import com.spotify.heroic.elasticsearch.Connection;
import com.spotify.heroic.elasticsearch.RateLimitedCache;
import com.spotify.heroic.elasticsearch.index.NoIndexSelectedException;
import com.spotify.heroic.filter.AndFilter;
import com.spotify.heroic.filter.FalseFilter;
import com.spotify.heroic.filter.Filter;
import com.spotify.heroic.filter.HasTagFilter;
import com.spotify.heroic.filter.MatchKeyFilter;
import com.spotify.heroic.filter.MatchTagFilter;
import com.spotify.heroic.filter.NotFilter;
import com.spotify.heroic.filter.OrFilter;
import com.spotify.heroic.filter.StartsWithFilter;
import com.spotify.heroic.filter.TrueFilter;
import com.spotify.heroic.lifecycle.LifeCycleRegistry;
import com.spotify.heroic.lifecycle.LifeCycles;
import com.spotify.heroic.metric.WriteResult;
import com.spotify.heroic.statistics.SuggestBackendReporter;
import com.spotify.heroic.suggest.KeySuggest;
import com.spotify.heroic.suggest.MatchOptions;
import com.spotify.heroic.suggest.SuggestBackend;
import com.spotify.heroic.suggest.TagKeyCount;
import com.spotify.heroic.suggest.TagSuggest;
import com.spotify.heroic.suggest.TagSuggest.Suggestion;
import com.spotify.heroic.suggest.TagValueSuggest;
import com.spotify.heroic.suggest.TagValuesSuggest;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Order;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.aggregations.metrics.cardinality.Cardinality;
import org.elasticsearch.search.aggregations.metrics.cardinality.CardinalityBuilder;
import org.elasticsearch.search.aggregations.metrics.tophits.TopHits;
import org.elasticsearch.search.aggregations.metrics.tophits.TopHitsBuilder;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.spotify.heroic.suggest.elasticsearch.ElasticsearchSuggestUtils.loadJsonResource;
import static org.elasticsearch.index.query.FilterBuilders.andFilter;
import static org.elasticsearch.index.query.FilterBuilders.boolFilter;
import static org.elasticsearch.index.query.FilterBuilders.matchAllFilter;
import static org.elasticsearch.index.query.FilterBuilders.notFilter;
import static org.elasticsearch.index.query.FilterBuilders.orFilter;
import static org.elasticsearch.index.query.FilterBuilders.prefixFilter;
import static org.elasticsearch.index.query.FilterBuilders.termFilter;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.filteredQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;

@ElasticsearchScope
@ToString(of = {"connection"})
public class SuggestBackendKV extends AbstractElasticsearchBackend
    implements SuggestBackend, Grouped, LifeCycles {
    public static final String WRITE_CACHE_SIZE = "write-cache-size";

    private static final String TAG_DELIMITER = "\0";
    private static final String SERIES_TYPE = "series";
    private static final String TAG_TYPE = "tag";
    private static final String KEY = "key";
    private static final String TAG_KEYS = "tag_keys";
    private static final String TAGS = "tags";
    private static final String SERIES_ID = "series_id";

    private static final String TAG_SKEY_RAW = "skey.raw";
    private static final String TAG_SKEY_PREFIX = "skey.prefix";
    private static final String TAG_SKEY = "skey";
    private static final String TAG_SVAL_RAW = "sval.raw";
    private static final String TAG_SVAL_PREFIX = "sval.prefix";
    private static final String TAG_SVAL = "sval";
    private static final String TAG_KV = "kv";

    private static final String SERIES_KEY_ANALYZED = "key.analyzed";
    private static final String SERIES_KEY_PREFIX = "key.prefix";
    private static final String SERIES_KEY = "key";

    public static final TimeValue TIMEOUT = TimeValue.timeValueSeconds(30);

    private static final String[] KEY_SUGGEST_SOURCES = new String[]{KEY};
    private static final String[] TAG_SUGGEST_SOURCES = new String[]{TAG_SKEY, TAG_SVAL};

    private final Managed<Connection> connection;
    private final SuggestBackendReporter reporter;

    /**
     * prevent unnecessary writes if entry is already in cache. Integer is the hashCode of the
     * series.
     */
    private final RateLimitedCache<Pair<String, HashCode>> writeCache;
    private final Groups groups;
    private final boolean configure;

    @Inject
    public SuggestBackendKV(
        final AsyncFramework async, final Managed<Connection> connection,
        final SuggestBackendReporter reporter,
        final RateLimitedCache<Pair<String, HashCode>> writeCache, final Groups groups,
        @Named("configure") boolean configure
    ) {
        super(async);
        this.connection = connection;
        this.reporter = reporter;
        this.writeCache = writeCache;
        this.groups = groups;
        this.configure = configure;
    }

    @Override
    public void register(LifeCycleRegistry registry) {
        registry.start(this::start);
        registry.stop(this::stop);
    }

    @Override
    public AsyncFuture<Void> configure() {
        return connection.doto(Connection::configure);
    }

    @Override
    public Groups groups() {
        return groups;
    }

    @Override
    public boolean isReady() {
        return connection.isReady();
    }

    @Override
    public AsyncFuture<TagValuesSuggest> tagValuesSuggest(
        final RangeFilter filter, final List<String> exclude, final OptionalLimit groupLimit
    ) {
        return connection.doto((final Connection c) -> {
            final BoolFilterBuilder bool = boolFilter();

            if (!(filter.getFilter() instanceof TrueFilter)) {
                bool.must(filter(filter.getFilter()));
            }

            for (final String e : exclude) {
                bool.mustNot(termFilter(TAG_SKEY_RAW, e));
            }

            final QueryBuilder query =
                bool.hasClauses() ? filteredQuery(matchAllQuery(), bool) : matchAllQuery();

            final SearchRequestBuilder request = c
                .search(filter.getRange(), TAG_TYPE)
                .setSearchType(SearchType.COUNT)
                .setQuery(query)
                .setTimeout(TIMEOUT);

            final OptionalLimit limit = filter.getLimit();

            {
                final TermsBuilder terms = AggregationBuilders.terms("keys").field(TAG_SKEY_RAW);

                limit.asInteger().ifPresent(l -> terms.size(l + 1));

                request.addAggregation(terms);
                // make value bucket one entry larger than necessary to figure out when limiting
                // is applied.
                final TermsBuilder cardinality =
                    AggregationBuilders.terms("values").field(TAG_SVAL_RAW);

                groupLimit.asInteger().ifPresent(l -> cardinality.size(l + 1));
                terms.subAggregation(cardinality);
            }

            return bind(request.execute()).directTransform((SearchResponse response) -> {
                final List<TagValuesSuggest.Suggestion> suggestions = new ArrayList<>();

                final Terms terms = response.getAggregations().get("keys");

                final List<Bucket> buckets = terms.getBuckets();

                for (final Terms.Bucket bucket : limit.limitList(buckets)) {
                    final Terms valueTerms = bucket.getAggregations().get("values");

                    final List<Bucket> valueBuckets = valueTerms.getBuckets();

                    final SortedSet<String> result = new TreeSet<>();

                    for (final Terms.Bucket valueBucket : valueBuckets) {
                        result.add(valueBucket.getKey());
                    }

                    final List<String> values = groupLimit.limitList(ImmutableList.copyOf(result));
                    final boolean limited = groupLimit.isGreater(valueBuckets.size());

                    suggestions.add(
                        new TagValuesSuggest.Suggestion(bucket.getKey(), values, limited));
                }

                return TagValuesSuggest.of(ImmutableList.copyOf(suggestions),
                    limit.isGreater(buckets.size()));
            });
        });
    }

    @Override
    public AsyncFuture<TagValueSuggest> tagValueSuggest(
        final RangeFilter filter, final Optional<String> key
    ) {
        return connection.doto((final Connection c) -> {
            final BoolQueryBuilder bool = boolQuery();

            key.ifPresent(k -> {
                if (!k.isEmpty()) {
                    bool.must(termQuery(TAG_SKEY_RAW, k));
                }
            });

            QueryBuilder query = bool.hasClauses() ? bool : matchAllQuery();

            if (!(filter.getFilter() instanceof TrueFilter)) {
                query = filteredQuery(query, filter(filter.getFilter()));
            }

            final SearchRequestBuilder request = c
                .search(filter.getRange(), TAG_TYPE)
                .setSearchType(SearchType.COUNT)
                .setQuery(query);

            final OptionalLimit limit = filter.getLimit();

            {
                final TermsBuilder terms =
                    AggregationBuilders.terms("values").field(TAG_SVAL_RAW).order(Order.term(true));

                limit.asInteger().ifPresent(l -> terms.size(l + 1));
                request.addAggregation(terms);
            }

            return bind(request.execute()).directTransform((SearchResponse response) -> {
                final ImmutableList.Builder<String> suggestions = ImmutableList.builder();

                final Terms terms = response.getAggregations().get("values");

                final List<Bucket> buckets = terms.getBuckets();

                for (final Terms.Bucket bucket : limit.limitList(buckets)) {
                    suggestions.add(bucket.getKey());
                }

                return TagValueSuggest.of(suggestions.build(), limit.isGreater(buckets.size()));
            });
        });
    }

    @Override
    public AsyncFuture<TagKeyCount> tagKeyCount(final RangeFilter filter) {
        return connection.doto((final Connection c) -> {
            final QueryBuilder root = filteredQuery(matchAllQuery(), filter(filter.getFilter()));

            final SearchRequestBuilder request = c
                .search(filter.getRange(), TAG_TYPE)
                .setSearchType(SearchType.COUNT)
                .setQuery(root);

            final OptionalLimit limit = filter.getLimit();

            {
                final TermsBuilder terms = AggregationBuilders.terms("keys").field(TAG_SKEY_RAW);

                limit.asInteger().ifPresent(l -> terms.size(l + 1));

                request.addAggregation(terms);
                final CardinalityBuilder cardinality =
                    AggregationBuilders.cardinality("cardinality").field(TAG_SVAL_RAW);
                terms.subAggregation(cardinality);
            }

            return bind(request.execute()).directTransform((SearchResponse response) -> {
                final Set<TagKeyCount.Suggestion> suggestions = new LinkedHashSet<>();

                final Terms terms = response.getAggregations().get("keys");

                final List<Bucket> buckets = terms.getBuckets();

                for (final Terms.Bucket bucket : limit.limitList(buckets)) {
                    final Cardinality cardinality = bucket.getAggregations().get("cardinality");
                    suggestions.add(
                        new TagKeyCount.Suggestion(bucket.getKey(), cardinality.getValue()));
                }

                return TagKeyCount.of(ImmutableList.copyOf(suggestions),
                    limit.isGreater(buckets.size()));
            });
        });
    }

    @Override
    public AsyncFuture<TagSuggest> tagSuggest(
        final RangeFilter filter, final MatchOptions options, final Optional<String> key,
        final Optional<String> value
    ) {
        return connection.doto((final Connection c) -> {
            final BoolQueryBuilder bool = boolQuery();

            // special case: key and value are equal, which indicates that _any_ match should be
            // in effect.
            // XXX: Enhance API to allow for this to be intentional instead of this by
            // introducing an 'any' field.
            if (key.isPresent() && value.isPresent() && key.equals(value)) {
                bool.should(matchTermValue(value.get()));
                bool.should(matchTermKey(key.get()));
            } else {
                key.ifPresent(k -> {
                    if (!k.isEmpty()) {
                        bool.must(matchTermKey(k).boost(2.0f));
                    }
                });

                value.ifPresent(v -> {
                    if (!v.isEmpty()) {
                        bool.must(matchTermValue(v));
                    }
                });
            }

            QueryBuilder query = bool.hasClauses() ? bool : matchAllQuery();

            if (!(filter.getFilter() instanceof TrueFilter)) {
                query = filteredQuery(query, filter(filter.getFilter()));
            }

            final SearchRequestBuilder request = c
                .search(filter.getRange(), TAG_TYPE)
                .setSearchType(SearchType.COUNT)
                .setQuery(query)
                .setTimeout(TIMEOUT);

            // aggregation
            {
                final TopHitsBuilder hits = AggregationBuilders
                    .topHits("hits")
                    .setSize(1)
                    .setFetchSource(TAG_SUGGEST_SOURCES, new String[0]);

                final TermsBuilder terms =
                    AggregationBuilders.terms("terms").field(TAG_KV).subAggregation(hits);

                filter.getLimit().asInteger().ifPresent(terms::size);

                request.addAggregation(terms);
            }

            return bind(request.execute()).directTransform((SearchResponse response) -> {
                final Set<Suggestion> suggestions = new LinkedHashSet<>();

                Aggregations aggregations = response.getAggregations();

                if (aggregations == null) {
                    return TagSuggest.of();
                }

                final StringTerms terms = aggregations.get("terms");

                for (final Terms.Bucket bucket : terms.getBuckets()) {
                    final TopHits topHits = bucket.getAggregations().get("hits");
                    final SearchHits hits = topHits.getHits();
                    final SearchHit hit = hits.getAt(0);
                    final Map<String, Object> doc = hit.getSource();

                    final String k = (String) doc.get(TAG_SKEY);
                    final String v = (String) doc.get(TAG_SVAL);

                    suggestions.add(new Suggestion(hits.getMaxScore(), k, v));
                }

                return TagSuggest.of(ImmutableList.copyOf(suggestions));
            });
        });
    }

    @Override
    public AsyncFuture<KeySuggest> keySuggest(
        final RangeFilter filter, final MatchOptions options, final Optional<String> key
    ) {
        return connection.doto((final Connection c) -> {
            BoolQueryBuilder bool = boolQuery();

            key.ifPresent(k -> {
                if (!k.isEmpty()) {
                    final String l = k.toLowerCase();
                    final BoolQueryBuilder b = boolQuery();
                    b.should(termQuery(SERIES_KEY_ANALYZED, l));
                    b.should(termQuery(SERIES_KEY_PREFIX, l).boost(1.5f));
                    b.should(termQuery(SERIES_KEY, l).boost(2.0f));
                    bool.must(b);
                }
            });

            QueryBuilder query = bool.hasClauses() ? bool : matchAllQuery();

            if (!(filter.getFilter() instanceof TrueFilter)) {
                query = filteredQuery(query, filter(filter.getFilter()));
            }

            final SearchRequestBuilder request = c
                .search(filter.getRange(), "series")
                .setSearchType(SearchType.COUNT)
                .setQuery(query);

            // aggregation
            {
                final TopHitsBuilder hits = AggregationBuilders
                    .topHits("hits")
                    .setSize(1)
                    .setFetchSource(KEY_SUGGEST_SOURCES, new String[0]);

                final TermsBuilder terms =
                    AggregationBuilders.terms("terms").field(KEY).subAggregation(hits);

                filter.getLimit().asInteger().ifPresent(terms::size);
                request.addAggregation(terms);
            }

            return bind(request.execute()).directTransform((SearchResponse response) -> {
                final Set<KeySuggest.Suggestion> suggestions = new LinkedHashSet<>();

                final StringTerms terms = response.getAggregations().get("terms");

                for (final Terms.Bucket bucket : terms.getBuckets()) {
                    final TopHits topHits = bucket.getAggregations().get("hits");
                    final SearchHits hits = topHits.getHits();
                    suggestions.add(new KeySuggest.Suggestion(hits.getMaxScore(), bucket.getKey()));
                }

                return KeySuggest.of(ImmutableList.copyOf(suggestions));
            });
        });
    }

    @Override
    public AsyncFuture<WriteResult> write(final Series s, final DateRange range) {
        return connection.doto((final Connection c) -> {
            final String[] indices;

            try {
                indices = c.writeIndices(range);
            } catch (NoIndexSelectedException e) {
                return async.failed(e);
            }

            final List<AsyncFuture<WriteResult>> writes = new ArrayList<>();

            for (final String index : indices) {
                final Pair<String, HashCode> key = Pair.of(index, s.getHashCode());

                if (!writeCache.acquire(key)) {
                    reporter.reportWriteDroppedByRateLimit();
                    continue;
                }

                final String seriesId = s.hash();

                final List<AsyncFuture<WriteResult>> w = new ArrayList<>();

                final XContentBuilder series = XContentFactory.jsonBuilder();

                series.startObject();
                buildContext(series, s);
                series.endObject();

                final long start = System.nanoTime();

                w.add(bind(c
                    .index(index, SERIES_TYPE)
                    .setId(seriesId)
                    .setSource(series)
                    .setOpType(OpType.CREATE)
                    .execute()).directTransform(
                    (response) -> WriteResult.of(System.nanoTime() - start)));

                for (final Map.Entry<String, String> e : s.getTags().entrySet()) {
                    final XContentBuilder suggest = XContentFactory.jsonBuilder();

                    suggest.startObject();
                    buildContext(suggest, s);
                    buildTag(suggest, e);
                    suggest.endObject();

                    final String suggestId = seriesId + ":" + Integer.toHexString(e.hashCode());

                    w.add(bind(c
                        .index(index, TAG_TYPE)
                        .setId(suggestId)
                        .setSource(suggest)
                        .setOpType(OpType.CREATE)
                        .execute()).directTransform(
                        (response) -> WriteResult.of(System.nanoTime() - start)));
                }

                writes.add(async.collect(w, WriteResult.merger()));
            }

            return async.collect(writes, WriteResult.merger());
        });
    }

    @Override
    public Statistics getStatistics() {
        return Statistics.of(WRITE_CACHE_SIZE, writeCache.size());
    }

    private AsyncFuture<Void> start() {
        final AsyncFuture<Void> future = connection.start();

        if (!configure) {
            return future;
        }

        return future.lazyTransform(v -> configure());
    }

    private AsyncFuture<Void> stop() {
        return connection.stop();
    }

    private static BoolQueryBuilder matchTermKey(final String key) {
        final String lkey = key.toLowerCase();

        return analyzedTermQuery(TAG_SKEY, lkey)
            .should(termQuery(TAG_SKEY_PREFIX, lkey).boost(1.5f))
            .should(termQuery(TAG_SKEY_RAW, key).boost(2f));
    }

    private static BoolQueryBuilder matchTermValue(final String value) {
        final String lvalue = value.toLowerCase();

        return analyzedTermQuery(TAG_SVAL, lvalue)
            .should(termQuery(TAG_SVAL_PREFIX, lvalue).boost(1.5f))
            .should(termQuery(TAG_SVAL_RAW, value).boost(2.0f));
    }

    private static final Splitter SPACE_SPLITTER =
        Splitter.on(Pattern.compile("[ \t]+")).omitEmptyStrings().trimResults();

    static BoolQueryBuilder analyzedTermQuery(final String field, final String value) {
        final BoolQueryBuilder builder = boolQuery();

        for (final String part : SPACE_SPLITTER.split(value)) {
            builder.should(termQuery(field, part));
        }

        return builder;
    }

    static void buildContext(final XContentBuilder b, final Series series) throws IOException {
        b.field(KEY, series.getKey());

        b.startArray(TAGS);

        for (final Map.Entry<String, String> entry : series.getTags().entrySet()) {
            b.value(entry.getKey() + TAG_DELIMITER + entry.getValue());
        }

        b.endArray();

        b.startArray(TAG_KEYS);

        for (final Map.Entry<String, String> entry : series.getTags().entrySet()) {
            b.value(entry.getKey());
        }

        b.endArray();

        b.field(SERIES_ID, series.hash());
    }

    static void buildTag(final XContentBuilder b, Entry<String, String> e) throws IOException {
        b.field(TAG_SKEY, e.getKey());
        b.field(TAG_SVAL, e.getValue());
        b.field(TAG_KV, e.getKey() + TAG_DELIMITER + e.getValue());
    }

    private static final Filter.Visitor<FilterBuilder> FILTER_CONVERTER =
        new Filter.Visitor<FilterBuilder>() {
            @Override
            public FilterBuilder visitTrue(final TrueFilter t) {
                return matchAllFilter();
            }

            @Override
            public FilterBuilder visitFalse(final FalseFilter f) {
                return notFilter(matchAllFilter());
            }

            @Override
            public FilterBuilder visitAnd(final AndFilter and) {
                final List<FilterBuilder> filters = new ArrayList<>(and.terms().size());

                for (final Filter stmt : and.terms()) {
                    filters.add(filter(stmt));
                }

                return andFilter(filters.toArray(new FilterBuilder[0]));
            }

            @Override
            public FilterBuilder visitOr(final OrFilter or) {
                final List<FilterBuilder> filters = new ArrayList<>(or.terms().size());

                for (final Filter stmt : or.terms()) {
                    filters.add(filter(stmt));
                }

                return orFilter(filters.toArray(new FilterBuilder[0]));
            }

            @Override
            public FilterBuilder visitNot(final NotFilter not) {
                return notFilter(filter(not.getFilter()));
            }

            @Override
            public FilterBuilder visitMatchTag(final MatchTagFilter matchTag) {
                return termFilter(TAGS, matchTag.getTag() + '\0' + matchTag.getValue());
            }

            @Override
            public FilterBuilder visitStartsWith(final StartsWithFilter startsWith) {
                return prefixFilter(TAGS, startsWith.getTag() + '\0' + startsWith.getValue());
            }

            @Override
            public FilterBuilder visitHasTag(final HasTagFilter hasTag) {
                return termFilter(TAG_KEYS, hasTag.getTag());
            }

            @Override
            public FilterBuilder visitMatchKey(final MatchKeyFilter matchKey) {
                return termFilter(KEY, matchKey.getValue());
            }

            @Override
            public FilterBuilder defaultAction(Filter filter) {
                throw new IllegalArgumentException("Unsupported filter: " + filter);
            }
        };

    public static FilterBuilder filter(final Filter filter) {
        return filter.visit(FILTER_CONVERTER);
    }

    public static Supplier<BackendType> factory() {
        return () -> {
            final Map<String, Map<String, Object>> mappings = new HashMap<>();
            mappings.put("tag", loadJsonResource("kv/tag.json"));
            mappings.put("series", loadJsonResource("kv/series.json"));

            final Map<String, Object> settings = loadJsonResource("kv/settings.json");

            return new BackendType(mappings, settings, SuggestBackendKV.class);
        };
    }
}
