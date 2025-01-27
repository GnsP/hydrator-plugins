/*
 * Copyright © 2016-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.batch.aggregator;

import org.junit.Assert;
import org.junit.Test;
import org.python.google.common.collect.ImmutableList;

/**
 * Tests for parsing and validation done by {@link DedupAggregator}.
 */
public class DedupConfigTest {

  @Test
  public void testParsing() {
    for (DedupConfig.Function function : DedupConfig.Function.values()) {
      DedupConfig config = new DedupConfig(" user, item, price ",
                                           String.format(" price   : %s   ", function.name().toLowerCase()));
      Assert.assertEquals(ImmutableList.of("user", "item", "price"), config.getUniqueFields());

      DedupConfig.DedupFunctionInfo expected = new DedupConfig.DedupFunctionInfo("price", function);
      DedupConfig.DedupFunctionInfo actual = config.getFilter();
      Assert.assertEquals(expected, actual);
    }
  }

  @Test
  public void testDeprecatedFirst() {
    DedupConfig config = new DedupConfig(" user, item, price ",
                                         "price:First (Deprecated)");

    DedupConfig.DedupFunctionInfo functionInfo = config.getFilter();
    Assert.assertEquals(DedupConfig.Function.FIRST, functionInfo.getFunction());
  }

  @Test
  public void testDeprecatedLast() {
    DedupConfig config = new DedupConfig(" user, item, price ",
                                         "price:Last (Deprecated)");

    DedupConfig.DedupFunctionInfo functionInfo = config.getFilter();
    Assert.assertEquals(DedupConfig.Function.LAST, functionInfo.getFunction());
  }
}
