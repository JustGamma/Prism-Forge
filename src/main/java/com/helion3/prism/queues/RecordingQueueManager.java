/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.helion3.prism.queues;

import java.util.ArrayList;
import java.util.List;

import com.helion3.prism.api.records.PrismRecord;
import com.helion3.prism.api.records.PrismRecordPreSaveEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.api.data.DataContainer;

import com.helion3.prism.Prism;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;

public class RecordingQueueManager implements Runnable {

    @Override
    public synchronized void run() {
        List<DataContainer> eventsSaveBatch = new ArrayList<>();

        // Assume we're iterating everything in the queue
        while (!RecordingQueue.getQueue().isEmpty()) {
            // Poll the next event, append to list
            PrismRecord record = RecordingQueue.getQueue().poll();

            if (record != null) {
                // Prepare PrismRecord for sending to a PrismRecordEvent
                PrismRecordPreSaveEvent preSaveEvent = new PrismRecordPreSaveEvent(record,
                    Cause.of(EventContext.empty(), Prism.getInstance()));

                // Preserve the pre-save cancellation point on Forge's native event bus.
                MinecraftForge.EVENT_BUS.post(preSaveEvent);

                if (!preSaveEvent.isCancelled()) {
                    eventsSaveBatch.add(record.getDataContainer());
                }
            }
        }

        if (eventsSaveBatch.size() > 0) {
            try {
                Prism.getInstance().getStorageAdapter().records().write(eventsSaveBatch);
            } catch (Exception e) {
                // @todo handle failures
                e.printStackTrace();
            }
        }
    }
}
