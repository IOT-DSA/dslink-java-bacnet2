package bacnet;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bacnet.DeviceFolder.CovListener;

import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.PropertyReferences;

public class BacnetPoller {
	private static final Logger LOGGER;
	static {
		LOGGER = LoggerFactory.getLogger(BacnetPoller.class);
	}
	
	private static final int MAX_SUBS_PER_POINT = 2;
	
	BacnetLink link;
	BacnetPoint point;
	
	private final CovListener listener;
	
	private boolean[] subscribed;
	private boolean cov;
	
	BacnetPoller(BacnetPoint p) {
		subscribed = new boolean[MAX_SUBS_PER_POINT];
		link = p.folder.conn.link;
		point = p;
		listener = point.folder.new CovListener(point);
	}
	
	void subscribe(int index, boolean iscov) {
		if (index >= MAX_SUBS_PER_POINT) return;
		boolean wasActive = isActive();
		boolean wasCov = cov;
		subscribed[index] = true;
		cov = iscov;
		if (!wasActive) {
			if (cov) startCov();
			else startPoll();
		} else if (wasCov != cov) {
			if (cov) { 
				stopPoll();
				startCov();
			} else {
				stopCov();
				startPoll();
			}
		}
		
	}
	void unsubscribe(int index) {
		boolean wasActive = isActive();
		subscribed[index] = false;
		if (wasActive && !isActive()) {
			if (cov) stopCov();
			else stopPoll();
		}
		

	}
	boolean isActive() {
		for (boolean b: subscribed) {
			if (b) return true;
		}
		return false;
	}
	
	void startPoll() {
		if (link.futures.containsKey(point)) {
			return;
        }
		ScheduledThreadPoolExecutor stpe = Objects.getDaemonThreadPool();
		ScheduledFuture<?> fut = stpe.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				if (point.folder.conn.localDevice == null) {
					point.folder.conn.stop();
					return;
				}
				if (point.node != null) LOGGER.debug("polling " + point.node.getName());
				getPoint(point, point.folder);
			}	                 
		}, 0, point.folder.root.interval, TimeUnit.MILLISECONDS);
		link.futures.put(point, fut);
	}
	
	void stopPoll() {
		ScheduledFuture<?> fut = link.futures.remove(point);
		if (fut != null) {
			fut.cancel(false);
		}
	}
	
	void startCov() {
		getPoint(point, point.folder);
		point.folder.setupCov(point, listener);
		final ArrayBlockingQueue<SequenceOf<PropertyValue>> covEv = listener.event;
		if (link.futures.containsKey(point)) {
			return;
        }
		ScheduledThreadPoolExecutor stpe = Objects.getDaemonThreadPool();
		ScheduledFuture<?> fut = stpe.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				SequenceOf<PropertyValue> listOfValues = covEv.poll();
				if (listOfValues != null) {
					for (PropertyValue pv: listOfValues) {
						if (point.node != null) LOGGER.debug("got cov for " + point.node.getName());
						point.folder.updatePointValue(point, pv.getPropertyIdentifier(), pv.getValue());
					}
				}
			}	                 
		}, 0, 50, TimeUnit.MILLISECONDS);
		link.futures.put(point, fut);
	}
	
	void stopCov() {
		ScheduledFuture<?> fut = link.futures.remove(point);
		if (fut != null) {
			fut.cancel(false);
		}
		//cl.event.active = false;
		if (point.folder.conn.localDevice != null) 
			point.folder.conn.localDevice.getEventHandler().removeListener(listener);
	}
	
	
	private static void getPoint(BacnetPoint point, DeviceFolder devicefold) {
		PropertyReferences refs = new PropertyReferences();
		Map<ObjectIdentifier, BacnetPoint> points = new HashMap<ObjectIdentifier, BacnetPoint>();
		ObjectIdentifier oid = point.oid;
      	DeviceFolder.addPropertyReferences(refs, oid);
      	points.put(oid, point);
      	devicefold.getProperties(refs, points);
	}
}
