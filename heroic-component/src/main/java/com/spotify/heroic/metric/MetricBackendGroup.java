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

package com.spotify.heroic.metric;

import com.spotify.heroic.QueryOptions;
import com.spotify.heroic.aggregation.AggregationInstance;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.filter.Filter;
import eu.toolchain.async.AsyncFuture;

public interface MetricBackendGroup extends MetricBackend {
    /**
     * Perform a direct query for data points.
     *
     * @param type MetricType source.
     * @param filter Filter to apply.
     * @param range Range of series to query.
     * @param aggregation Aggregation method to use.
     * @param options Options that defines specific behaviours of the query.
     * @return The result in the form of MetricGroups.
     * @throws BackendGroupException
     */
    AsyncFuture<ResultGroups> query(
        MetricType type, Filter filter, DateRange range, AggregationInstance aggregation,
        QueryOptions options
    );

    /**
     * Fetch metrics with a default (no-op) quota watcher. This method allows for the fetching of an
     * indefinite amount of metrics.
     *
     * @see MetricBackend#fetch
     */
    AsyncFuture<FetchData> fetch(
        MetricType type, Series series, DateRange range, QueryOptions options
    );
}
