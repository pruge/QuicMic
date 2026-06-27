//! Lock-free single-producer single-consumer (SPSC) ring buffer.
//!
//! Used to stream audio between the network receiver task (producer) and the
//! cpal output callback (consumer) without locks.

use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicUsize, Ordering};

/// A lock-free SPSC ring buffer for streaming audio between the network
/// receiver task (producer) and the cpal output callback (consumer).
///
/// Uses Acquire/Release ordering: the producer stores head with Release
/// after writing data, and the consumer loads head with Acquire before
/// reading. This guarantees data visibility across cores (including ARM).
pub struct RingBuffer {
    buf: Box<[UnsafeCell<i16>]>,
    capacity: usize,
    head: AtomicUsize, // write position (producer)
    tail: AtomicUsize, // read position (consumer)
}

impl RingBuffer {
    /// Create a new ring buffer that can hold `capacity` samples.
    /// Actual internal size is `capacity + 1` to distinguish full from empty.
    pub fn new(capacity: usize) -> Self {
        let internal = capacity + 1;
        let buf: Vec<UnsafeCell<i16>> = (0..internal).map(|_| UnsafeCell::new(0)).collect();
        Self {
            buf: buf.into_boxed_slice(),
            capacity: internal,
            head: AtomicUsize::new(0),
            tail: AtomicUsize::new(0),
        }
    }

    /// Approximate number of samples currently in the buffer.
    /// Uses Relaxed ordering — suitable for monitoring, not synchronization.
    #[inline]
    pub fn len(&self) -> usize {
        let head = self.head.load(Ordering::Relaxed);
        let tail = self.tail.load(Ordering::Relaxed);
        if head >= tail {
            head - tail
        } else {
            self.capacity - tail + head
        }
    }

    /// Maximum number of samples the buffer can hold.
    #[inline]
    pub fn capacity(&self) -> usize {
        self.capacity - 1
    }

    /// Push samples into the buffer using batch memcpy. Returns the number of
    /// samples actually written. Drops excess samples silently if the buffer
    /// is full (acceptable for real-time audio).
    pub fn push(&self, samples: &[i16]) -> usize {
        let head = self.head.load(Ordering::Relaxed); // Only producer writes head
        let tail = self.tail.load(Ordering::Acquire); // Sync with consumer's Release

        let free = if head >= tail {
            self.capacity - 1 - head + tail
        } else {
            tail - head - 1
        };

        let to_write = samples.len().min(free);
        if to_write == 0 {
            return 0;
        }

        // Batch copy in two contiguous segments (handles wrap-around).
        // SAFETY: UnsafeCell<i16> is #[repr(transparent)] so the boxed slice
        // has identical layout to [i16]. Only the producer writes, and the
        // consumer reads behind the tail — no data races.
        let first = to_write.min(self.capacity - head);
        unsafe {
            let base = self.buf[0].get();
            std::ptr::copy_nonoverlapping(samples.as_ptr(), base.add(head), first);
            let second = to_write - first;
            if second > 0 {
                std::ptr::copy_nonoverlapping(samples.as_ptr().add(first), base, second);
            }
        }

        self.head
            .store((head + to_write) % self.capacity, Ordering::Release);
        to_write
    }

    /// Pop up to `out.len()` samples. Returns the number of samples read.
    /// Fills remaining slots with silence (zero) on underrun.
    pub fn pop(&self, out: &mut [i16]) -> usize {
        let head = self.head.load(Ordering::Acquire); // Sync with producer's Release
        let tail = self.tail.load(Ordering::Relaxed); // Only consumer writes tail

        let avail = if head >= tail {
            head - tail
        } else {
            self.capacity - tail + head
        };

        let to_read = out.len().min(avail);

        // Batch read in two contiguous segments
        let first = to_read.min(self.capacity - tail);
        unsafe {
            let base = self.buf[0].get() as *const i16;
            std::ptr::copy_nonoverlapping(base.add(tail), out.as_mut_ptr(), first);
            let second = to_read - first;
            if second > 0 {
                std::ptr::copy_nonoverlapping(base, out.as_mut_ptr().add(first), second);
            }
        }

        // Fill remaining with silence
        for slot in &mut out[to_read..] {
            *slot = 0;
        }

        self.tail
            .store((tail + to_read) % self.capacity, Ordering::Release);
        to_read
    }
}

// SAFETY: RingBuffer uses UnsafeCell for interior mutability with a strict
// SPSC access pattern. Acquire/Release ordering on head/tail ensures
// proper memory synchronization between producer and consumer threads.
unsafe impl Send for RingBuffer {}
unsafe impl Sync for RingBuffer {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_push_pop_roundtrip() {
        let rb = RingBuffer::new(8);
        assert_eq!(rb.len(), 0);
        assert_eq!(rb.capacity(), 8);

        assert_eq!(rb.push(&[1, 2, 3, 4]), 4);
        assert_eq!(rb.len(), 4);

        let mut out = [0i16; 4];
        assert_eq!(rb.pop(&mut out), 4);
        assert_eq!(out, [1, 2, 3, 4]);
        assert_eq!(rb.len(), 0);
    }

    #[test]
    fn ring_drops_excess_when_full() {
        let rb = RingBuffer::new(4);
        // Only `capacity` samples fit; the rest are dropped.
        assert_eq!(rb.push(&[1, 2, 3, 4, 5, 6]), 4);
        assert_eq!(rb.len(), 4);
    }

    #[test]
    fn ring_underrun_fills_silence() {
        let rb = RingBuffer::new(8);
        rb.push(&[7, 7]);

        let mut out = [123i16; 4];
        assert_eq!(rb.pop(&mut out), 2);
        // The two unread slots are zero-filled.
        assert_eq!(out, [7, 7, 0, 0]);
    }

    #[test]
    fn ring_wraps_around() {
        let rb = RingBuffer::new(4);
        rb.push(&[1, 2, 3]);
        let mut out = [0i16; 3];
        rb.pop(&mut out);
        assert_eq!(out, [1, 2, 3]);

        // head/tail are now near the end; this push must wrap.
        assert_eq!(rb.push(&[4, 5, 6]), 3);
        let mut out2 = [0i16; 3];
        assert_eq!(rb.pop(&mut out2), 3);
        assert_eq!(out2, [4, 5, 6]);
    }
}
