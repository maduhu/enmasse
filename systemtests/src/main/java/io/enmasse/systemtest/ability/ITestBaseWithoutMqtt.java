/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.ability;

import io.enmasse.systemtest.AddressType;

public interface ITestBaseWithoutMqtt extends ITestBase {

    @Override
    default String getDefaultPlan(AddressType addressType) {
        return "standard-anycast";
    }

    default String getAddressSpacePlan() {
        return "unlimited-standard-without-mqtt";
    }
}
