/*
 *
 * Copyright (c) Lightstreamer Srl
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package stocklist_jms_demo.common;

import java.io.Serializable;
import java.util.HashMap;

/**
 * A message published by Generator and received from Adapter.
 */
public class FeedMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    //the item name
    public String itemName = null;
    //an HashMap containing the updates for the item (the field names are the keys)
    public HashMap currentValues = null;
    //indicate if the map carries the entire snapshot for the item
    public boolean isSnapshot = false;
    //the id related to the handle of this item
    public String handleId = null;
    //the id related to this generator's life
    public int random;

    public FeedMessage(String itemName, final HashMap currentValues, boolean isSnapshot, String handleId, int random) {
        this.itemName = itemName;
        this.currentValues = currentValues;
        this.isSnapshot = isSnapshot;
        this.handleId = handleId;
        this.random = random;
    }

}