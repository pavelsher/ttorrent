package com.turn.ttorrent;

public abstract class WaitFor {
  private long myPollInterval = 10;

  protected WaitFor() {
    this(120 * 1000);
  }

  protected WaitFor(String optionalWatchDogName, long timeout) {
    long started = System.currentTimeMillis();
    try {
      while(true) {
        if (condition()) return;
        if (System.currentTimeMillis() - started < timeout) {
          Thread.sleep(myPollInterval);
        } else {
          break;
        }
      }

    } catch (InterruptedException e) {
      //NOP
    }
  }

  protected WaitFor(long timeout) {
    this(null, timeout);
  }

  protected WaitFor(long timeout, long pollInterval) {
    this(null, timeout);
    myPollInterval = pollInterval;
  }

  protected abstract boolean condition();
}