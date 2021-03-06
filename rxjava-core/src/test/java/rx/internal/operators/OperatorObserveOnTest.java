/**
 * Copyright 2014 Netflix, Inc.
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
 */
package rx.internal.operators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;
import org.mockito.InOrder;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observer;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.exceptions.MissingBackpressureException;
import rx.exceptions.TestException;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.internal.util.RxRingBuffer;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;

public class OperatorObserveOnTest {

    /**
     * This is testing a no-op path since it uses Schedulers.immediate() which will not do scheduling.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testObserveOn() {
        Observer<Integer> observer = mock(Observer.class);
        Observable.from(1, 2, 3).observeOn(Schedulers.immediate()).subscribe(observer);

        verify(observer, times(1)).onNext(1);
        verify(observer, times(1)).onNext(2);
        verify(observer, times(1)).onNext(3);
        verify(observer, times(1)).onCompleted();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testOrdering() throws InterruptedException {
        Observable<String> obs = Observable.from("one", null, "two", "three", "four");

        Observer<String> observer = mock(Observer.class);

        InOrder inOrder = inOrder(observer);
        TestSubscriber<String> ts = new TestSubscriber<String>(observer);

        obs.observeOn(Schedulers.computation()).subscribe(ts);

        ts.awaitTerminalEvent(1000, TimeUnit.MILLISECONDS);
        if (ts.getOnErrorEvents().size() > 0) {
            for (Throwable t : ts.getOnErrorEvents()) {
                t.printStackTrace();
            }
            fail("failed with exception");
        }

        inOrder.verify(observer, times(1)).onNext("one");
        inOrder.verify(observer, times(1)).onNext(null);
        inOrder.verify(observer, times(1)).onNext("two");
        inOrder.verify(observer, times(1)).onNext("three");
        inOrder.verify(observer, times(1)).onNext("four");
        inOrder.verify(observer, times(1)).onCompleted();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testThreadName() throws InterruptedException {
        System.out.println("Main Thread: " + Thread.currentThread().getName());
        Observable<String> obs = Observable.from("one", null, "two", "three", "four");

        Observer<String> observer = mock(Observer.class);
        final String parentThreadName = Thread.currentThread().getName();

        final CountDownLatch completedLatch = new CountDownLatch(1);

        // assert subscribe is on main thread
        obs = obs.doOnNext(new Action1<String>() {

            @Override
            public void call(String s) {
                String threadName = Thread.currentThread().getName();
                System.out.println("Source ThreadName: " + threadName + "  Expected => " + parentThreadName);
                assertEquals(parentThreadName, threadName);
            }

        });

        // assert observe is on new thread
        obs.observeOn(Schedulers.newThread()).doOnNext(new Action1<String>() {

            @Override
            public void call(String t1) {
                String threadName = Thread.currentThread().getName();
                boolean correctThreadName = threadName.startsWith("RxNewThreadScheduler");
                System.out.println("ObserveOn ThreadName: " + threadName + "  Correct => " + correctThreadName);
                assertTrue(correctThreadName);
            }

        }).finallyDo(new Action0() {

            @Override
            public void call() {
                completedLatch.countDown();

            }
        }).subscribe(observer);

        if (!completedLatch.await(1000, TimeUnit.MILLISECONDS)) {
            fail("timed out waiting");
        }

        verify(observer, never()).onError(any(Throwable.class));
        verify(observer, times(5)).onNext(any(String.class));
        verify(observer, times(1)).onCompleted();
    }

    @Test
    public void observeOnTheSameSchedulerTwice() {
        Scheduler scheduler = Schedulers.immediate();

        Observable<Integer> o = Observable.from(1, 2, 3);
        Observable<Integer> o2 = o.observeOn(scheduler);

        @SuppressWarnings("unchecked")
        Observer<Object> observer1 = mock(Observer.class);
        @SuppressWarnings("unchecked")
        Observer<Object> observer2 = mock(Observer.class);

        InOrder inOrder1 = inOrder(observer1);
        InOrder inOrder2 = inOrder(observer2);

        o2.subscribe(observer1);
        o2.subscribe(observer2);

        inOrder1.verify(observer1, times(1)).onNext(1);
        inOrder1.verify(observer1, times(1)).onNext(2);
        inOrder1.verify(observer1, times(1)).onNext(3);
        inOrder1.verify(observer1, times(1)).onCompleted();
        verify(observer1, never()).onError(any(Throwable.class));
        inOrder1.verifyNoMoreInteractions();

        inOrder2.verify(observer2, times(1)).onNext(1);
        inOrder2.verify(observer2, times(1)).onNext(2);
        inOrder2.verify(observer2, times(1)).onNext(3);
        inOrder2.verify(observer2, times(1)).onCompleted();
        verify(observer2, never()).onError(any(Throwable.class));
        inOrder2.verifyNoMoreInteractions();
    }

    @Test
    public void observeSameOnMultipleSchedulers() {
        TestScheduler scheduler1 = new TestScheduler();
        TestScheduler scheduler2 = new TestScheduler();

        Observable<Integer> o = Observable.from(1, 2, 3);
        Observable<Integer> o1 = o.observeOn(scheduler1);
        Observable<Integer> o2 = o.observeOn(scheduler2);

        @SuppressWarnings("unchecked")
        Observer<Object> observer1 = mock(Observer.class);
        @SuppressWarnings("unchecked")
        Observer<Object> observer2 = mock(Observer.class);

        InOrder inOrder1 = inOrder(observer1);
        InOrder inOrder2 = inOrder(observer2);

        o1.subscribe(observer1);
        o2.subscribe(observer2);

        scheduler1.advanceTimeBy(1, TimeUnit.SECONDS);
        scheduler2.advanceTimeBy(1, TimeUnit.SECONDS);

        inOrder1.verify(observer1, times(1)).onNext(1);
        inOrder1.verify(observer1, times(1)).onNext(2);
        inOrder1.verify(observer1, times(1)).onNext(3);
        inOrder1.verify(observer1, times(1)).onCompleted();
        verify(observer1, never()).onError(any(Throwable.class));
        inOrder1.verifyNoMoreInteractions();

        inOrder2.verify(observer2, times(1)).onNext(1);
        inOrder2.verify(observer2, times(1)).onNext(2);
        inOrder2.verify(observer2, times(1)).onNext(3);
        inOrder2.verify(observer2, times(1)).onCompleted();
        verify(observer2, never()).onError(any(Throwable.class));
        inOrder2.verifyNoMoreInteractions();
    }

    /**
     * Confirm that running on a NewThreadScheduler uses the same thread for the entire stream
     */
    @Test
    public void testObserveOnWithNewThreadScheduler() {
        final AtomicInteger count = new AtomicInteger();
        final int _multiple = 99;

        Observable.range(1, 100000).map(new Func1<Integer, Integer>() {

            @Override
            public Integer call(Integer t1) {
                return t1 * _multiple;
            }

        }).observeOn(Schedulers.newThread())
                .toBlocking().forEach(new Action1<Integer>() {

                    @Override
                    public void call(Integer t1) {
                        assertEquals(count.incrementAndGet() * _multiple, t1.intValue());
                        assertTrue(Thread.currentThread().getName().startsWith("RxNewThreadScheduler"));
                    }

                });

    }

    /**
     * Confirm that running on a ThreadPoolScheduler allows multiple threads but is still ordered.
     */
    @Test
    public void testObserveOnWithThreadPoolScheduler() {
        final AtomicInteger count = new AtomicInteger();
        final int _multiple = 99;

        Observable.range(1, 100000).map(new Func1<Integer, Integer>() {

            @Override
            public Integer call(Integer t1) {
                return t1 * _multiple;
            }

        }).observeOn(Schedulers.computation())
                .toBlocking().forEach(new Action1<Integer>() {

                    @Override
                    public void call(Integer t1) {
                        assertEquals(count.incrementAndGet() * _multiple, t1.intValue());
                        assertTrue(Thread.currentThread().getName().startsWith("RxComputationThreadPool"));
                    }

                });
    }

    /**
     * Attempts to confirm that when pauses exist between events, the ScheduledObserver
     * does not lose or reorder any events since the scheduler will not block, but will
     * be re-scheduled when it receives new events after each pause.
     * 
     * 
     * This is non-deterministic in proving success, but if it ever fails (non-deterministically)
     * it is a sign of potential issues as thread-races and scheduling should not affect output.
     */
    @Test
    public void testObserveOnOrderingConcurrency() {
        final AtomicInteger count = new AtomicInteger();
        final int _multiple = 99;

        Observable.range(1, 10000).map(new Func1<Integer, Integer>() {

            @Override
            public Integer call(Integer t1) {
                if (randomIntFrom0to100() > 98) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                return t1 * _multiple;
            }

        }).observeOn(Schedulers.computation())
                .toBlocking().forEach(new Action1<Integer>() {

                    @Override
                    public void call(Integer t1) {
                        assertEquals(count.incrementAndGet() * _multiple, t1.intValue());
                        assertTrue(Thread.currentThread().getName().startsWith("RxComputationThreadPool"));
                    }

                });
    }

    @Test
    public void testNonBlockingOuterWhileBlockingOnNext() throws InterruptedException {

        final CountDownLatch completedLatch = new CountDownLatch(1);
        final CountDownLatch nextLatch = new CountDownLatch(1);
        final AtomicLong completeTime = new AtomicLong();
        // use subscribeOn to make async, observeOn to move
        Observable.range(1, 2).subscribeOn(Schedulers.newThread()).observeOn(Schedulers.newThread()).subscribe(new Observer<Integer>() {

            @Override
            public void onCompleted() {
                System.out.println("onCompleted");
                completeTime.set(System.nanoTime());
                completedLatch.countDown();
            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onNext(Integer t) {
                // don't let this thing finish yet
                try {
                    if (!nextLatch.await(1000, TimeUnit.MILLISECONDS)) {
                        throw new RuntimeException("it shouldn't have timed out");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException("it shouldn't have failed");
                }
            }

        });

        long afterSubscribeTime = System.nanoTime();
        System.out.println("After subscribe: " + completedLatch.getCount());
        assertEquals(1, completedLatch.getCount());
        nextLatch.countDown();
        completedLatch.await(1000, TimeUnit.MILLISECONDS);
        assertTrue(completeTime.get() > afterSubscribeTime);
        System.out.println("onComplete nanos after subscribe: " + (completeTime.get() - afterSubscribeTime));
    }

    private static int randomIntFrom0to100() {
        // XORShift instead of Math.random http://javamex.com/tutorials/random_numbers/xorshift.shtml
        long x = System.nanoTime();
        x ^= (x << 21);
        x ^= (x >>> 35);
        x ^= (x << 4);
        return Math.abs((int) x % 100);
    }

    @Test
    public void testDelayedErrorDeliveryWhenSafeSubscriberUnsubscribes() {
        TestScheduler testScheduler = new TestScheduler();

        Observable<Integer> source = Observable.concat(Observable.<Integer> error(new TestException()), Observable.just(1));

        @SuppressWarnings("unchecked")
        Observer<Integer> o = mock(Observer.class);
        InOrder inOrder = inOrder(o);

        source.observeOn(testScheduler).subscribe(o);

        inOrder.verify(o, never()).onError(any(TestException.class));

        testScheduler.advanceTimeBy(1, TimeUnit.SECONDS);

        inOrder.verify(o).onError(any(TestException.class));
        inOrder.verify(o, never()).onNext(anyInt());
        inOrder.verify(o, never()).onCompleted();
    }
    
    @Test
    public void testAfterUnsubscribeCalledThenObserverOnNextNeverCalled() {
        final TestScheduler testScheduler = new TestScheduler();
        final Observer<Integer> observer = mock(Observer.class);
        final Subscription subscription = Observable.from(1, 2, 3)
                .observeOn(testScheduler)
                .subscribe(observer);
        subscription.unsubscribe();
        testScheduler.advanceTimeBy(1, TimeUnit.SECONDS);

        final InOrder inOrder = inOrder(observer);

        inOrder.verify(observer, never()).onNext(anyInt());
        inOrder.verify(observer, never()).onError(any(Exception.class));
        inOrder.verify(observer, never()).onCompleted();
    }

    @Test
    public void testBackpressureWithTakeAfter() {
        final AtomicInteger generated = new AtomicInteger();
        Observable<Integer> observable = Observable.from(new Iterable<Integer>() {
            @Override
            public Iterator<Integer> iterator() {
                return new Iterator<Integer>() {

                    @Override
                    public void remove() {
                    }

                    @Override
                    public Integer next() {
                        return generated.getAndIncrement();
                    }

                    @Override
                    public boolean hasNext() {
                        return true;
                    }
                };
            }
        });

        TestSubscriber<Integer> testSubscriber = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                System.err.println("c t = " + t + " thread " + Thread.currentThread());
                super.onNext(t);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
            }
        };

        observable
                .observeOn(Schedulers.newThread())
                .take(3)
                .subscribe(testSubscriber);
        testSubscriber.awaitTerminalEvent();
        System.err.println(testSubscriber.getOnNextEvents());
        testSubscriber.assertReceivedOnNext(Arrays.asList(0, 1, 2));
        // it should be between the take num and requested batch size across the async boundary
        System.out.println("Generated: " + generated.get());
        assertTrue(generated.get() >= 3 && generated.get() <= RxRingBuffer.SIZE);
    }

    @Test
    public void testBackpressureWithTakeAfterAndMultipleBatches() {
        int numForBatches = RxRingBuffer.SIZE * 3 + 1; // should be 4 batches == ((3*n)+1) items
        final AtomicInteger generated = new AtomicInteger();
        Observable<Integer> observable = Observable.from(new Iterable<Integer>() {
            @Override
            public Iterator<Integer> iterator() {
                return new Iterator<Integer>() {

                    @Override
                    public void remove() {
                    }

                    @Override
                    public Integer next() {
                        return generated.getAndIncrement();
                    }

                    @Override
                    public boolean hasNext() {
                        return true;
                    }
                };
            }
        });

        TestSubscriber<Integer> testSubscriber = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                //                System.err.println("c t = " + t + " thread " + Thread.currentThread());
                super.onNext(t);
            }
        };

        observable
                .observeOn(Schedulers.newThread())
                .take(numForBatches)
                .subscribe(testSubscriber);
        testSubscriber.awaitTerminalEvent();
        System.err.println(testSubscriber.getOnNextEvents());
        // it should be between the take num and requested batch size across the async boundary
        System.out.println("Generated: " + generated.get());
        assertTrue(generated.get() >= numForBatches && generated.get() <= numForBatches + RxRingBuffer.SIZE);
    }

    @Test
    public void testBackpressureWithTakeBefore() {
        final AtomicInteger generated = new AtomicInteger();
        Observable<Integer> observable = Observable.from(new Iterable<Integer>() {
            @Override
            public Iterator<Integer> iterator() {
                return new Iterator<Integer>() {

                    @Override
                    public void remove() {
                    }

                    @Override
                    public Integer next() {
                        return generated.getAndIncrement();
                    }

                    @Override
                    public boolean hasNext() {
                        return true;
                    }
                };
            }
        });

        TestSubscriber<Integer> testSubscriber = new TestSubscriber<Integer>();
        observable
                .take(7)
                .observeOn(Schedulers.newThread())
                .subscribe(testSubscriber);

        testSubscriber.awaitTerminalEvent();
        testSubscriber.assertReceivedOnNext(Arrays.asList(0, 1, 2, 3, 4, 5, 6));
        assertEquals(7, generated.get());
    }

    @Test
    public void testQueueFullEmitsError() {
        final CountDownLatch latch = new CountDownLatch(1);
        Observable<Integer> observable = Observable.create(new OnSubscribe<Integer>() {

            @Override
            public void call(Subscriber<? super Integer> o) {
                for (int i = 0; i < RxRingBuffer.SIZE + 10; i++) {
                    o.onNext(i);
                }
                latch.countDown();
                o.onCompleted();
            }

        });

        TestSubscriber<Integer> testSubscriber = new TestSubscriber<Integer>(new Observer<Integer>() {

            @Override
            public void onCompleted() {

            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onNext(Integer t) {
                // force it to be slow and wait until we have queued everything
                try {
                    latch.await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        });
        observable.observeOn(Schedulers.newThread()).subscribe(testSubscriber);

        testSubscriber.awaitTerminalEvent();
        List<Throwable> errors = testSubscriber.getOnErrorEvents();
        assertEquals(1, errors.size());
        System.out.println("Errors: " + errors);
        Throwable t = errors.get(0);
        if (t instanceof MissingBackpressureException) {
            // success, we expect this
        } else {
            if (t.getCause() instanceof MissingBackpressureException) {
                // this is also okay
            } else {
                fail("Expecting MissingBackpressureException");
            }
        }
    }

    @Test
    public void testAsyncChild() {
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>();
        Observable.range(0, 100000).observeOn(Schedulers.newThread()).observeOn(Schedulers.newThread()).subscribe(ts);
        ts.awaitTerminalEvent();
        ts.assertNoErrors();
    }
}
