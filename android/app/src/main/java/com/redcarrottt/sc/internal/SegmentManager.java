package com.redcarrottt.sc.internal;

/* Copyright (c) 2017-2018. All rights reserved.
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *  Eunsoo Park (esevan.park@gmail.com)
 *  Injung Hwang (sinban04@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.redcarrottt.testapp.Logger;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.ListIterator;

/*
    Segment is the minimum unit of the sending data through the network.
    Segment header (sequence # + flag_len) is 8bytes (4bytes + 4bytes)
 */
class Segment {
    int seq_no;
    int flag_len;
    byte[] data;

    Segment() {
        seq_no = -1;
        flag_len = 0;
        data = new byte[SegmentManager.kSegSize + SegmentManager.kSegHeaderSize];
    }
}

class SegmentManager {
    static private SegmentManager instance = null;
    static public final int kSegSize = 512;
    private static final int kSegFreeThreshold = 256;

    static public final int kSegHeaderSize = 8;

    // kSegLenOffset: not used
    // private static final int kSegLenOffset = 0;
    private static final int kSegFlagOffset = 14;
    private static final int kSegLenMask = 0x00003fff;
    private static final int kSegFlagMask = 0x0000C000;

    static final int kSQSendData = 0;
    static final int kSQRecvData = 1;
    static final int kSQSendControl = 2;
    static final int kSQRecvControl = 3;
    private static final int kNumSQ = 4;
    static final int kSQUnknown = 999;

    static final int kDeqSendControlData = 0;
    static final int kDeqRecvData = 1;
    static final int kDeqRecvControl = 2;
    private static final int kNumDeq = 3;

    static final int kSNData = 0;
    static final int kSNControl = 1;
    private static final int kNumSN = 2;

    static final short kSegFlagMF = 1;
    static final short kSegFlagControl = 2;

    private int[] mNextSeqNo;
    private int[] mExpectedSeqNo;

    private LinkedList[] mQueues;
    private Object[] mDequeueCond;
    private final LinkedList<Segment> mFailedSendingQueue;
    private LinkedList[] mPendingQueue;
    private int[] mQueueLengths;

    private static String kTag = "SegmentManager";

    private final LinkedList<Segment> mFreeSegments;
    private int mFreeSegmentsSize;

    // Macro
    private static int mGetSegLenBits(int x) {
        return (x & kSegLenMask);
    }

    public static int mGetSegFlagBits(int x) {
        return (x & kSegFlagMask) >> kSegFlagOffset;
    }

    private static int mSetSegLenBits(int x, int dest) {
        return (dest | (x & kSegLenMask));
    }

    private static int mSetSegFlagBits(int x, int dest) {
        return (dest | ((x << kSegFlagOffset) & kSegFlagMask));
    }

    private SegmentManager() {
        this.mQueues = new LinkedList[kNumSQ];
        for (int i = 0; i < kNumSQ; i++) {
            this.mQueues[i] = new LinkedList<Segment>();
        }

        this.mDequeueCond = new Object[kNumDeq];
        for (int i = 0; i < kNumDeq; i++) {
            this.mDequeueCond[i] = new Object();
        }

        this.mFailedSendingQueue = new LinkedList<Segment>();
        this.mPendingQueue = new LinkedList[kNumSQ];
        for (int i = 0; i < kNumSQ; i++) {
            this.mPendingQueue[i] = new LinkedList<Segment>();
        }

        this.mQueueLengths = new int[kNumSQ];
        for (int i = 0; i < kNumSQ; i++) {
            this.mQueueLengths[i] = 0;
        }

        this.mExpectedSeqNo = new int[kNumSQ];
        for (int i = 0; i < kNumSQ; i++) {
            this.mExpectedSeqNo[i] = 0;
        }

        this.mNextSeqNo = new int[kNumSN];
        for (int i = 0; i < kNumSN; i++) {
            this.mNextSeqNo[i] = 0;
        }

        this.mFreeSegments = new LinkedList<Segment>();
    }


    static public SegmentManager singleton() {
        if (instance == null) instance = new SegmentManager();

        return instance;
    }

    private int get_next_seq_no(int seq_num_type, int length) {
        int ret = mNextSeqNo[seq_num_type];
        mNextSeqNo[seq_num_type] += length;
        return ret;
    }

    public int send_to_segment_manager(byte[] data, int length, boolean isControl) {
        if (data == null || length <= 0) throw new AssertionError();

        int offset = 0;
        int num_of_segments = (length + kSegSize - 1) / kSegSize;
        int seq_num_type = (isControl) ? kSNControl : kSNData;
        int allocated_seq_no = get_next_seq_no(seq_num_type, num_of_segments);
        int seg_idx;
        for (seg_idx = 0; seg_idx < num_of_segments; seg_idx++) {
            int seg_len = (length - offset < kSegSize) ? (length - offset) : kSegSize;
            Segment seg = get_free_segment();

            seg.flag_len = mSetSegLenBits(seg_len, seg.flag_len);
            seg.seq_no = allocated_seq_no++;

            System.arraycopy(data, offset, seg.data, kSegHeaderSize, seg_len);
            offset += seg_len;

            int flag = 0;
            if (offset < length) flag |= kSegFlagMF;
            if (isControl) flag |= kSegFlagControl;
            seg.flag_len = mSetSegFlagBits(flag, seg.flag_len);

            serialize_segment_header(seg);

            if (isControl) {
                enqueue(kSQSendControl, seg);
            } else {
                enqueue(kSQSendData, seg);
            }
        }

        return 0;
    }

    private void serialize_segment_header(Segment segment) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(segment.seq_no);
        byte[] net_seq_no = buffer.array();

        buffer = ByteBuffer.allocate(4);
        buffer.putInt(segment.flag_len);
        byte[] net_flag_len = buffer.array();

        System.arraycopy(net_seq_no, 0, segment.data, 0, 4);
        System.arraycopy(net_flag_len, 0, segment.data, 4, 4);
    }

    public byte[] recv_from_segment_manager(ProtocolData protocolData, boolean isControl) {
        if (protocolData == null) throw new AssertionError();

        byte[] serialized;
        int offset = 0;
        int data_size;
        boolean cont;

        Segment seg;
        do {
            if (isControl) {
                seg = dequeue(kDeqRecvControl);
            } else {
                seg = dequeue(kDeqRecvData);
            }
        } while(seg == null);
        ProtocolManager.parse_header(Arrays.copyOfRange(seg.data, kSegHeaderSize, seg.data
                .length), protocolData);
        if (protocolData.len == 0) return null;

        //Logger.DEBUG(kTag, "pd.len is " + pd.len);
        serialized = new byte[protocolData.len];

        // Handle the first segment of the data bulk, because it contains protocol data
        data_size = mGetSegLenBits(seg.flag_len) - ProtocolManager.kProtocolHeaderSize;
        System.arraycopy(seg.data, kSegHeaderSize + ProtocolManager.kProtocolHeaderSize,
                serialized, offset, data_size);
        offset += data_size;

        cont = ((mGetSegFlagBits(seg.flag_len) & kSegFlagMF) != 0);
        free_segment(seg);

        while (cont) {
            do {
                if (isControl) {
                    seg = dequeue(kDeqRecvControl);
                } else {
                    seg = dequeue(kDeqRecvData);
                }
            } while(seg == null);
            data_size = mGetSegLenBits(seg.flag_len);
            System.arraycopy(seg.data, kSegHeaderSize, serialized, offset, data_size);
            cont = ((mGetSegFlagBits(seg.flag_len) & kSegFlagMF) != 0);
            offset += data_size;
            free_segment(seg);
        }

        return serialized;
    }

    public void enqueue(int queueType, Segment segment) {
        if (queueType >= kNumSQ) throw new AssertionError();

        int dequeueType;
        switch (queueType) {
            case kSQRecvControl:
                dequeueType = kDeqRecvControl;
                break;
            case kSQRecvData:
                dequeueType = kDeqRecvData;
                break;
            case kSQSendControl:
            case kSQSendData:
                dequeueType = kDeqSendControlData;
                break;
            default:
                Logger.ERR(kTag, "Enqueue: Unknown queue type: " + queueType);
                return;
        }

        synchronized (mDequeueCond[dequeueType]) {
            boolean segmentEnqueued = false;

            synchronized(this.mQueues[queueType]) {
                if (segment.seq_no == mExpectedSeqNo[queueType]) {
                    mExpectedSeqNo[queueType]++;
                    mQueues[queueType].offerLast(segment);
                    mQueueLengths[queueType]++;
                    segmentEnqueued = true;
                } else {
                    if (segment.seq_no < mExpectedSeqNo[queueType]) {
                        // If duplicated data comes, ignore it.
                        Logger.WARN(kTag, "Sequence No Error: (" + queueType + ") incoming=" + segment.seq_no + " / expected_next=" + mExpectedSeqNo[queueType]);


                        return;
                    }

                    ListIterator it = mPendingQueue[queueType].listIterator();
                    while (it.hasNext()) {
                        Segment walker = (Segment) it.next();
                        if (walker.seq_no > segment.seq_no) break;
                    }
                    Logger.DEBUG(kTag, "Insert to pending queue: (" + queueType + ") incoming=" + segment.seq_no + " / expected_next=" + mExpectedSeqNo[queueType]);

                    it.add(segment);
                }

                ListIterator it = mPendingQueue[queueType].listIterator();
                while (it.hasNext()) {
                    Segment walker = (Segment) it.next();

                    if (walker.seq_no != mExpectedSeqNo[queueType]) break;

                    mQueues[queueType].offerLast(walker);
                    mQueueLengths[queueType]++;
                    mExpectedSeqNo[queueType]++;
                    segmentEnqueued = true;

                    it.remove();
                }
            }

            if (segmentEnqueued) {
                this.mDequeueCond[dequeueType].notifyAll();
            }
        }
    }

    public Segment dequeue(int dequeueType) {
        assert (dequeueType < kNumDeq);
        synchronized (this.mDequeueCond[dequeueType]) {
            // If queue is empty, wait until some segment is enqueued
            boolean isWaitRequired = false;
            switch (dequeueType) {
                case kDeqSendControlData:
                    isWaitRequired = ((this.mQueueLengths[kSQSendControl] == 0) && (this
                            .mQueueLengths[kSQSendData] == 0));
                    break;
                case kDeqRecvControl:
                    isWaitRequired = (this.mQueueLengths[kSQRecvControl] == 0);
                    break;
                case kDeqRecvData:
                    isWaitRequired = (this.mQueueLengths[kSQRecvData] == 0);
                    break;
            }
            if (isWaitRequired) {
                try {
                    this.mDequeueCond[dequeueType].wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }


            // Dequeue from queue
            int targetQueueType = kSQUnknown;
            switch (dequeueType) {
                case kDeqSendControlData:
                    if (this.mQueueLengths[kSQSendControl] != 0) {
                        // Priority 1. Dequeue send control queue
                        targetQueueType = kSQSendControl;
                    } else if (this.mQueueLengths[kSQSendData] != 0) {
                        // Priority 2. Dequeue send data queue
                        targetQueueType = kSQSendData;
                    } else {
                        return null;
                    }
                    break;
                case kDeqRecvControl:
                    targetQueueType = kSQRecvControl;
                    break;
                case kDeqRecvData:
                    targetQueueType = kSQRecvData;
                    break;
                default:
                    Logger.ERR(kTag, "Dequeue failed: invalid dequeue type (Dequeue=" +
                            dequeueType + ")");
                    return null;
            }

            // Check queue type
            if (targetQueueType >= kNumSQ) {
                Logger.ERR(kTag, "Dequeue failed: invalid queue type (Dequeue=" + dequeueType +
                        ")");
                return null;
            }

            synchronized(this.mQueues[targetQueueType]) {
                // Check the dequeued segment
                Segment segmentDequeued = (Segment) this.mQueues[targetQueueType].pollFirst();
                mQueueLengths[targetQueueType]--;
                return segmentDequeued;
            }
        }
    }

    public Segment get_free_segment() {
        Segment ret;
        synchronized (mFreeSegments) {
            if (mFreeSegmentsSize == 0) {
                ret = new Segment();
            } else {
                ret = mFreeSegments.pop();
                mFreeSegmentsSize--;
            }

            if (ret == null) throw new AssertionError();

            ret.seq_no = -1;
            ret.flag_len = 0;
        }
        return ret;
    }

    public void free_segment(Segment seg) {
        synchronized (mFreeSegments) {
            mFreeSegments.push(seg);
            mFreeSegmentsSize++;

            if (mFreeSegmentsSize > kSegFreeThreshold) {
                release_segment_from_free_list(kSegFreeThreshold / 2);
            }
        }
    }

    private void release_segment_from_free_list(int threshold) {
        while (mFreeSegmentsSize > threshold) {
            mFreeSegments.pop();
            mFreeSegmentsSize--;
        }
    }

    public void failed_sending(Segment seg) {
        synchronized (mFailedSendingQueue) {
            mFailedSendingQueue.offerLast(seg);
        }
    }

    public Segment get_failed_sending() {
        Segment ret;
        synchronized (mFailedSendingQueue) {
            ret = mFailedSendingQueue.pollFirst();
        }

        return ret;
    }
}