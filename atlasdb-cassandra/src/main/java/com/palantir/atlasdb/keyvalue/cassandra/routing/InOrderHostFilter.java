/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.keyvalue.cassandra.routing;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

public class InOrderHostFilter implements HostFilter {

    private final List<HostFilter> hostFilters;

    public InOrderHostFilter(List<HostFilter> hostFilters) {
        this.hostFilters = hostFilters;
    }

    @Override
    public Set<InetSocketAddress> filter(Set<InetSocketAddress> desiredHosts) {
        for (HostFilter hostFilter : hostFilters) {
            desiredHosts = hostFilter.filter(desiredHosts);
        }
        return desiredHosts;
    }
}
