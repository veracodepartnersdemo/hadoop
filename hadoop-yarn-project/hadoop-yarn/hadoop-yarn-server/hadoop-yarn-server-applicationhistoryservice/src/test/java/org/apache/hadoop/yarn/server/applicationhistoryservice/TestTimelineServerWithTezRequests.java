/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.applicationhistoryservice;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import org.apache.hadoop.yarn.api.records.timeline.reader.TimelineEntityReader;
import org.apache.hadoop.yarn.client.api.TimelineClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.timeline.MemoryTimelineStore;
import org.apache.hadoop.yarn.server.timeline.TimelineStore;
import org.apache.hadoop.yarn.webapp.YarnJacksonJaxbJsonProvider;
import org.codehaus.jettison.json.JSONObject;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.jettison.JettisonFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static org.apache.hadoop.yarn.conf.YarnConfiguration.TIMELINE_HTTP_AUTH_PREFIX;
import static org.junit.jupiter.api.Assertions.*;

public class TestTimelineServerWithTezRequests {
    private static final Logger LOG = LoggerFactory.getLogger(TestTimelineServerWithTezRequests.class);

    private static final String BASEDIR =
            System.getProperty("test.build.dir", "target/test-dir") + "/"
                    + TestTimelineServerWithTezRequests.class.getSimpleName();
    private static final String TIMELINE_SERVICE_WEBAPP_ADDRESS = "localhost:8188";
    private static final String TEZ_ENTITY_TYPE = "TEZ_APPLICATION";
    private static final String TEZ_ENTITY_ID = "tez_application_1_2";
    private static ApplicationHistoryServer testTimelineServer;
    private static Configuration conf;

    @BeforeAll
    public static void setup() {
        try {
            testTimelineServer = new ApplicationHistoryServer();
            conf = new Configuration(false);
            conf.setStrings(TIMELINE_HTTP_AUTH_PREFIX + "type", "simple");

            conf.setBoolean(YarnConfiguration.TIMELINE_SERVICE_ENABLED, true);
            conf.setClass(YarnConfiguration.TIMELINE_SERVICE_STORE, MemoryTimelineStore.class, TimelineStore.class);
            conf.set(YarnConfiguration.TIMELINE_SERVICE_WEBAPP_ADDRESS, TIMELINE_SERVICE_WEBAPP_ADDRESS);
            conf.setInt(YarnConfiguration.TIMELINE_SERVICE_CLIENT_MAX_RETRIES, 1);

            testTimelineServer.init(conf);
            testTimelineServer.start();
        } catch (Exception e) {
            LOG.error("Failed to setup TimelineServer", e);
            fail("Couldn't setup TimelineServer");
        }
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (testTimelineServer != null) {
            testTimelineServer.stop();
        }
    }

    @Test
    void testPutAndGetTimelineEntity() throws Exception {
        putEntity();
        getEntity();
    }

    private void putEntity() throws IOException, YarnException {
        try (TimelineClient client = createTimelineClient()) {
            TimelineEntity entityToStore = new TimelineEntity();
            entityToStore.setEntityType(TEZ_ENTITY_TYPE);
            entityToStore.setEntityId(TEZ_ENTITY_ID);
            entityToStore.setStartTime(System.currentTimeMillis());
            TimelinePutResponse putResponse = client.putEntities(entityToStore);
            if (!putResponse.getErrors().isEmpty()) {
                LOG.error("putResponse errors: {}", putResponse.getErrors());
            }
            assertTrue(putResponse.getErrors().isEmpty(), "There were some errors in the putResponse");
            TimelineEntity entityToRead =
                    testTimelineServer.getTimelineStore().getEntity(TEZ_ENTITY_ID, TEZ_ENTITY_TYPE, null);
            assertNotNull(entityToRead, "Timeline entity should not be null");
        }
    }

    private TimelineClient createTimelineClient() {
        TimelineClient client = TimelineClient.createTimelineClient();
        client.init(conf);
        client.start();
        return client;
    }

    private void getEntity() {
        String appUrl = "http://" + TIMELINE_SERVICE_WEBAPP_ADDRESS + "/ws/v1/timeline/"
                + TEZ_ENTITY_TYPE + "/" + TEZ_ENTITY_ID + "?user.name=foo";
        LOG.info("Getting timeline entity for tez application: " + appUrl);

        ClientConfig cfg = new ClientConfig();
        cfg.register(new JettisonFeature()).register(YarnJacksonJaxbJsonProvider.class);
        Client client = ClientBuilder.newClient(cfg);
        client.register(TimelineEntityReader.class);
        WebTarget target = client.target(appUrl);

        Response response = target.request(MediaType.APPLICATION_JSON).get(Response.class);
        assertEquals(200, response.getStatus());
        assertTrue(MediaType.APPLICATION_JSON_TYPE.isCompatible(response.getMediaType()));

        JSONObject entityStr = response.readEntity(JSONObject.class);
        LOG.info("Got entity from ATS: {}", entityStr);
    }
}