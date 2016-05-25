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

package com.spotify.heroic.suggest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.spotify.heroic.cluster.ClusterShardGroup;
import com.spotify.heroic.common.OptionalLimit;
import com.spotify.heroic.metric.RequestError;
import com.spotify.heroic.metric.ShardError;
import eu.toolchain.async.Collector;
import eu.toolchain.async.Transform;
import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.google.common.base.Preconditions.checkNotNull;

@Data
public class TagValuesSuggest {
    public static final List<RequestError> EMPTY_ERRORS = new ArrayList<>();
    public static final List<Suggestion> EMPTY_SUGGESTIONS = new ArrayList<>();

    private final List<RequestError> errors;
    private final List<Suggestion> suggestions;
    private final boolean limited;

    @JsonCreator
    public TagValuesSuggest(
        @JsonProperty("errors") List<RequestError> errors,
        @JsonProperty("suggestions") List<Suggestion> suggestions,
        @JsonProperty("limited") Boolean limited
    ) {
        this.errors = checkNotNull(errors, "errors");
        this.suggestions = checkNotNull(suggestions, "suggestions");
        this.limited = checkNotNull(limited, "limited");
    }

    public TagValuesSuggest(List<Suggestion> suggestions, boolean limited) {
        this(EMPTY_ERRORS, suggestions, limited);
    }

    public static Collector<TagValuesSuggest, TagValuesSuggest> reduce(
        final OptionalLimit limit, final OptionalLimit groupLimit
    ) {
        return groups -> {
            final Map<String, MidFlight> midflights = new HashMap<>();
            boolean limited1 = false;

            for (final TagValuesSuggest g : groups) {
                for (final Suggestion s : g.suggestions) {
                    MidFlight m = midflights.get(s.key);

                    if (m == null) {
                        m = new MidFlight();
                        midflights.put(s.key, m);
                    }

                    m.values.addAll(s.values);
                    m.limited = m.limited || s.limited;
                }

                limited1 = limited1 || g.limited;
            }

            final ArrayList<Suggestion> suggestions1 = new ArrayList<>(midflights.size());

            for (final Map.Entry<String, MidFlight> e : midflights.entrySet()) {
                final String key = e.getKey();
                final MidFlight m = e.getValue();

                final ImmutableList<String> values = ImmutableList.copyOf(m.values);
                final boolean sLimited = m.limited || groupLimit.isGreater(values.size());

                suggestions1.add(new Suggestion(key, groupLimit.limitList(values), sLimited));
            }

            return new TagValuesSuggest(limit.limitList(suggestions1),
                limited1 || limit.isGreater(suggestions1.size()));
        };
    }

    @Data
    public static final class Suggestion {
        private final String key;
        private final List<String> values;
        private final boolean limited;

        @JsonCreator
        public Suggestion(
            @JsonProperty("key") String key, @JsonProperty("values") List<String> values,
            @JsonProperty("limited") Boolean limited
        ) {
            this.key = checkNotNull(key, "key");
            this.values = ImmutableList.copyOf(checkNotNull(values, "values"));
            this.limited = checkNotNull(limited, "limited");
        }

        // sort suggestions descending by score.
        public static final Comparator<Suggestion> COMPARATOR = new Comparator<Suggestion>() {
            @Override
            public int compare(Suggestion a, Suggestion b) {
                final int v = Integer.compare(b.values.size(), a.values.size());

                if (v != 0) {
                    return v;
                }

                return compareKey(a, b);
            }

            private int compareKey(Suggestion a, Suggestion b) {
                if (a.key == null && b.key == null) {
                    return 0;
                }

                if (a.key == null) {
                    return 1;
                }

                if (b.key == null) {
                    return -1;
                }

                return a.key.compareTo(b.key);
            }
        };
    }

    private static class MidFlight {
        private final TreeSet<String> values = new TreeSet<>();
        private boolean limited;
    }

    public static Transform<Throwable, TagValuesSuggest> shardError(
        final ClusterShardGroup shard
    ) {
        return e -> new TagValuesSuggest(ImmutableList.of(ShardError.fromThrowable(shard, e)),
            EMPTY_SUGGESTIONS, false);
    }
}
