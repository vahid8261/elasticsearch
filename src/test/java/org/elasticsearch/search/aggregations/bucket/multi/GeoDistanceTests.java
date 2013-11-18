/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.bucket.multi;

import com.google.common.collect.Sets;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.bucket.multi.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.multi.range.geodistance.GeoDistance;
import org.elasticsearch.search.aggregations.bucket.multi.terms.Terms;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNull.notNullValue;

/**
 *
 */
public class GeoDistanceTests extends ElasticsearchIntegrationTest {

    @Override
    public Settings indexSettings() {
        return ImmutableSettings.builder()
                .put("index.number_of_shards", between(1, 5))
                .put("index.number_of_replicas", between(0, 1))
                .build();
    }

    private IndexRequestBuilder indexCity(String name, String latLon) throws Exception {
        XContentBuilder source = jsonBuilder().startObject().field("city", name);
        if (latLon != null) {
            source = source.field("location", latLon);
        }
        source = source.endObject();
        return client().prepareIndex("idx", "type").setSource(source);
    }

    @Before
    public void init() throws Exception {
        prepareCreate("idx")
                .addMapping("type", "location", "type=geo_point", "city", "type=string,index=not_analyzed")
                .execute().actionGet();

        createIndex("idx_unmapped");

        List<IndexRequestBuilder> cities = new ArrayList<IndexRequestBuilder>();
        cities.addAll(Arrays.asList(
                // below 500km
                indexCity("utrecht", "52.0945, 5.116"),
                indexCity("haarlem", "52.3890, 4.637"),
                // above 500km, below 1000km
                indexCity("berlin", "52.540, 13.409"),
                indexCity("prague", "50.086, 14.439"),
                // above 1000km
                indexCity("tel-aviv", "32.0741, 34.777")));

        // random cities with no location
        for (String cityName : Arrays.asList("london", "singapour", "tokyo", "milan")) {
            if (randomBoolean() || true) {
                cities.add(indexCity(cityName, null));
            }
        }

        indexRandom(true, cities);
    }

    @Test
    public void simple() throws Exception {
        SearchResponse response = client().prepareSearch("idx")
                .addAggregation(geoDistance("amsterdam_rings")
                        .field("location")
                        .unit(DistanceUnit.KILOMETERS)
                        .point("52.3760, 4.894") // coords of amsterdam
                        .addUnboundedTo(500)
                        .addRange(500, 1000)
                        .addUnboundedFrom(1000))
                        .execute().actionGet();

        assertThat(response.getFailedShards(), equalTo(0));

        GeoDistance geoDist = response.getAggregations().get("amsterdam_rings");
        assertThat(geoDist, notNullValue());
        assertThat(geoDist.getName(), equalTo("amsterdam_rings"));
        assertThat(geoDist.buckets().size(), equalTo(3));

        GeoDistance.Bucket bucket = geoDist.getByKey("*-500.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("*-500.0"));
        assertThat(bucket.getFrom(), equalTo(0.0));
        assertThat(bucket.getTo(), equalTo(500.0));
        assertThat(bucket.getDocCount(), equalTo(2l));

        bucket = geoDist.getByKey("500.0-1000.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("500.0-1000.0"));
        assertThat(bucket.getFrom(), equalTo(500.0));
        assertThat(bucket.getTo(), equalTo(1000.0));
        assertThat(bucket.getDocCount(), equalTo(2l));

        bucket = geoDist.getByKey("1000.0-*");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("1000.0-*"));
        assertThat(bucket.getFrom(), equalTo(1000.0));
        assertThat(bucket.getTo(), equalTo(Double.POSITIVE_INFINITY));
        assertThat(bucket.getDocCount(), equalTo(1l));
    }

    @Test
    public void simple_WithCustomKeys() throws Exception {
        SearchResponse response = client().prepareSearch("idx")
                .addAggregation(geoDistance("amsterdam_rings")
                        .field("location")
                        .unit(DistanceUnit.KILOMETERS)
                        .point("52.3760, 4.894") // coords of amsterdam
                        .addUnboundedTo("ring1", 500)
                        .addRange("ring2", 500, 1000)
                        .addUnboundedFrom("ring3", 1000))
                .execute().actionGet();

        assertThat(response.getFailedShards(), equalTo(0));

        GeoDistance geoDist = response.getAggregations().get("amsterdam_rings");
        assertThat(geoDist, notNullValue());
        assertThat(geoDist.getName(), equalTo("amsterdam_rings"));
        assertThat(geoDist.buckets().size(), equalTo(3));

        GeoDistance.Bucket bucket = geoDist.getByKey("ring1");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("ring1"));
        assertThat(bucket.getFrom(), equalTo(0.0));
        assertThat(bucket.getTo(), equalTo(500.0));
        assertThat(bucket.getDocCount(), equalTo(2l));

        bucket = geoDist.getByKey("ring2");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("ring2"));
        assertThat(bucket.getFrom(), equalTo(500.0));
        assertThat(bucket.getTo(), equalTo(1000.0));
        assertThat(bucket.getDocCount(), equalTo(2l));

        bucket = geoDist.getByKey("ring3");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("ring3"));
        assertThat(bucket.getFrom(), equalTo(1000.0));
        assertThat(bucket.getTo(), equalTo(Double.POSITIVE_INFINITY));
        assertThat(bucket.getDocCount(), equalTo(1l));
    }

    @Test
    public void unmapped() throws Exception {
        client().admin().cluster().prepareHealth("idx_unmapped").setWaitForYellowStatus().execute().actionGet();

        SearchResponse response = client().prepareSearch("idx_unmapped")
                .addAggregation(geoDistance("amsterdam_rings")
                        .field("location")
                        .unit(DistanceUnit.KILOMETERS)
                        .point("52.3760, 4.894") // coords of amsterdam
                        .addUnboundedTo(500)
                        .addRange(500, 1000)
                        .addUnboundedFrom(1000))
                .execute().actionGet();

        assertThat(response.getFailedShards(), equalTo(0));

        GeoDistance geoDist = response.getAggregations().get("amsterdam_rings");
        assertThat(geoDist, notNullValue());
        assertThat(geoDist.getName(), equalTo("amsterdam_rings"));
        assertThat(geoDist.buckets().size(), equalTo(3));

        GeoDistance.Bucket bucket = geoDist.getByKey("*-500.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("*-500.0"));
        assertThat(bucket.getFrom(), equalTo(0.0));
        assertThat(bucket.getTo(), equalTo(500.0));
        assertThat(bucket.getDocCount(), equalTo(0l));

        bucket = geoDist.getByKey("500.0-1000.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("500.0-1000.0"));
        assertThat(bucket.getFrom(), equalTo(500.0));
        assertThat(bucket.getTo(), equalTo(1000.0));
        assertThat(bucket.getDocCount(), equalTo(0l));

        bucket = geoDist.getByKey("1000.0-*");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("1000.0-*"));
        assertThat(bucket.getFrom(), equalTo(1000.0));
        assertThat(bucket.getTo(), equalTo(Double.POSITIVE_INFINITY));
        assertThat(bucket.getDocCount(), equalTo(0l));
    }

    @Test
    public void partiallyUnmapped() throws Exception {
        client().admin().cluster().prepareHealth("idx_unmapped").setWaitForYellowStatus().execute().actionGet();

        SearchResponse response = client().prepareSearch("idx", "idx_unmapped")
                .addAggregation(geoDistance("amsterdam_rings")
                        .field("location")
                        .unit(DistanceUnit.KILOMETERS)
                        .point("52.3760, 4.894") // coords of amsterdam
                        .addUnboundedTo(500)
                        .addRange(500, 1000)
                        .addUnboundedFrom(1000))
                .execute().actionGet();

        assertThat(response.getFailedShards(), equalTo(0));

        GeoDistance geoDist = response.getAggregations().get("amsterdam_rings");
        assertThat(geoDist, notNullValue());
        assertThat(geoDist.getName(), equalTo("amsterdam_rings"));
        assertThat(geoDist.buckets().size(), equalTo(3));

        GeoDistance.Bucket bucket = geoDist.getByKey("*-500.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("*-500.0"));
        assertThat(bucket.getFrom(), equalTo(0.0));
        assertThat(bucket.getTo(), equalTo(500.0));
        assertThat(bucket.getDocCount(), equalTo(2l));

        bucket = geoDist.getByKey("500.0-1000.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("500.0-1000.0"));
        assertThat(bucket.getFrom(), equalTo(500.0));
        assertThat(bucket.getTo(), equalTo(1000.0));
        assertThat(bucket.getDocCount(), equalTo(2l));

        bucket = geoDist.getByKey("1000.0-*");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("1000.0-*"));
        assertThat(bucket.getFrom(), equalTo(1000.0));
        assertThat(bucket.getTo(), equalTo(Double.POSITIVE_INFINITY));
        assertThat(bucket.getDocCount(), equalTo(1l));
    }


    @Test
    public void withSubAggregation() throws Exception {
        SearchResponse response = client().prepareSearch("idx")
                .addAggregation(geoDistance("amsterdam_rings")
                        .field("location")
                        .unit(DistanceUnit.KILOMETERS)
                        .point("52.3760, 4.894") // coords of amsterdam
                        .addUnboundedTo(500)
                        .addRange(500, 1000)
                        .addUnboundedFrom(1000)
                        .subAggregation(terms("cities").field("city")))
                .execute().actionGet();

        assertThat(response.getFailedShards(), equalTo(0));

        GeoDistance geoDist = response.getAggregations().get("amsterdam_rings");
        assertThat(geoDist, notNullValue());
        assertThat(geoDist.getName(), equalTo("amsterdam_rings"));
        assertThat(geoDist.buckets().size(), equalTo(3));

        GeoDistance.Bucket bucket = geoDist.getByKey("*-500.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("*-500.0"));
        assertThat(bucket.getFrom(), equalTo(0.0));
        assertThat(bucket.getTo(), equalTo(500.0));
        assertThat(bucket.getDocCount(), equalTo(2l));
        assertThat(bucket.getAggregations().asList().isEmpty(), is(false));
        Terms cities = bucket.getAggregations().get("cities");
        assertThat(cities, Matchers.notNullValue());
        Set<String> names = Sets.newHashSet();
        for (Terms.Bucket city : cities) {
            names.add(city.getTerm().string());
        }
        assertThat(names.contains("utrecht") && names.contains("haarlem"), is(true));

        bucket = geoDist.getByKey("500.0-1000.0");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("500.0-1000.0"));
        assertThat(bucket.getFrom(), equalTo(500.0));
        assertThat(bucket.getTo(), equalTo(1000.0));
        assertThat(bucket.getDocCount(), equalTo(2l));
        assertThat(bucket.getAggregations().asList().isEmpty(), is(false));
        cities = bucket.getAggregations().get("cities");
        assertThat(cities, Matchers.notNullValue());
        names = Sets.newHashSet();
        for (Terms.Bucket city : cities) {
            names.add(city.getTerm().string());
        }
        assertThat(names.contains("berlin") && names.contains("prague"), is(true));

        bucket = geoDist.getByKey("1000.0-*");
        assertThat(bucket, notNullValue());
        assertThat(bucket.getKey(), equalTo("1000.0-*"));
        assertThat(bucket.getFrom(), equalTo(1000.0));
        assertThat(bucket.getTo(), equalTo(Double.POSITIVE_INFINITY));
        assertThat(bucket.getDocCount(), equalTo(1l));
        assertThat(bucket.getAggregations().asList().isEmpty(), is(false));
        cities = bucket.getAggregations().get("cities");
        assertThat(cities, Matchers.notNullValue());
        names = Sets.newHashSet();
        for (Terms.Bucket city : cities) {
            names.add(city.getTerm().string());
        }
        assertThat(names.contains("tel-aviv"), is(true));
    }

    @Test
    public void emptyAggregation() throws Exception {
        prepareCreate("empty_bucket_idx").addMapping("type", "value", "type=integer", "location", "type=geo_point").execute().actionGet();
        List<IndexRequestBuilder> builders = new ArrayList<IndexRequestBuilder>();
        for (int i = 0; i < 2; i++) {
            builders.add(client().prepareIndex("empty_bucket_idx", "type", "" + i).setSource(jsonBuilder()
                    .startObject()
                    .field("value", i * 2)
                    .field("location", "52.0945, 5.116")
                    .endObject()));
        }
        indexRandom(true, builders.toArray(new IndexRequestBuilder[builders.size()]));

        SearchResponse searchResponse = client().prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(histogram("histo").field("value").interval(1l).computeEmptyBuckets(true)
                        .subAggregation(geoDistance("geo_dist").field("location").point("52.3760, 4.894").addRange("0-100", 0.0, 100.0)))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2l));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, Matchers.notNullValue());
        Histogram.Bucket bucket = histo.getByKey(1l);
        assertThat(bucket, Matchers.notNullValue());

        GeoDistance geoDistance = bucket.getAggregations().get("geo_dist");
        assertThat(geoDistance, Matchers.notNullValue());
        assertThat(geoDistance.getName(), equalTo("geo_dist"));
        assertThat(geoDistance.buckets().size(), is(1));
        assertThat(geoDistance.buckets().get(0).getKey(), equalTo("0-100"));
        assertThat(geoDistance.buckets().get(0).getFrom(), equalTo(0.0));
        assertThat(geoDistance.buckets().get(0).getTo(), equalTo(100.0));
        assertThat(geoDistance.buckets().get(0).getDocCount(), equalTo(0l));

    }
}
