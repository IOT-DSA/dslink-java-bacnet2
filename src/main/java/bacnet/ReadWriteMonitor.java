package bacnet;

public class ReadWriteMonitor {
	
	private int activeReaders = 0;
	private int waitingReaders = 0;
	private int activeWriters = 0;
	private int waitingWriters = 0;
	
	
	public void checkInReader() throws InterruptedException {
		synchronized(this) {
			while (activeWriters + waitingWriters > 0) {
				waitingReaders += 1;
				try {
					this.wait();
				} finally {
					waitingReaders -= 1;
				}
			}
			activeReaders += 1;
		}
	}

	public void checkOutReader() {
		synchronized(this) {
			activeReaders -= 1;
			if (activeReaders == 0 && waitingWriters > 0) {
				this.notify();
			}
		}
	}
	
	public void checkInWriter() throws InterruptedException {
		synchronized(this) {
			while(activeWriters + activeReaders > 0) {
				waitingWriters += 1;
				try {
					this.wait();
				} finally {
					waitingWriters -= 1;
				}
			}
			activeWriters += 1;
		}	
	}
	
	public void checkOutWriter() {
		synchronized(this) {
			activeWriters -= 1;
			this.notifyAll();
		}
	}
}
