package com.yizhaoqi.smartpai.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileProcessingBackpressureTest {

    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final AtomicReference<Double> heapUsage = new AtomicReference<>(0.5);
    private final AtomicLong clock = new AtomicLong(0);
    private FileProcessingListenerGate gate;
    private FileProcessingBackpressureMonitor monitor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainer(FileProcessingListenerGate.LISTENER_ID)).thenReturn(container);
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        gate = new FileProcessingListenerGate(provider);
        monitor = new FileProcessingBackpressureMonitor(gate, true, 0.8, 0.7, 1000, 60, heapUsage::get, clock::get);
    }

    @Test
    void pausesAboveThresholdAndResumesBelowResumeThreshold() {
        monitor.check();
        verify(container, never()).pause();

        heapUsage.set(0.85);
        monitor.check();
        monitor.check();
        verify(container, times(1)).pause();
        assertTrue(gate.isPaused());

        heapUsage.set(0.75);
        monitor.check();
        verify(container, never()).resume();
        assertTrue(monitor.isPausedByMemory(), "处于两个阈值之间时保持暂停（迟滞）");

        heapUsage.set(0.6);
        monitor.check();
        verify(container).resume();
        assertFalse(gate.isPaused());
    }

    @Test
    void forcesResumeAfterMaxPauseDuration() {
        heapUsage.set(0.9);
        monitor.check();
        clock.set(59_000);
        monitor.check();
        verify(container, never()).resume();

        clock.set(60_000);
        monitor.check();
        verify(container).resume();
        assertFalse(monitor.isPausedByMemory());
    }

    @Test
    void gateOnlyResumesWhenAllReasonsAreCleared() {
        gate.pause(FileProcessingListenerGate.REASON_INDEX_SWITCH);
        heapUsage.set(0.9);
        monitor.check();
        verify(container, times(1)).pause();

        heapUsage.set(0.1);
        monitor.check();
        verify(container, never()).resume();
        assertEquals(1, gate.pauseReasons().size());

        gate.resume(FileProcessingListenerGate.REASON_INDEX_SWITCH);
        verify(container).resume();
    }

    @Test
    void rejectsInvertedThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileProcessingBackpressureMonitor(gate, true, 0.7, 0.8, 1000, 60, heapUsage::get, clock::get));
    }
}
