package bacnet;

import java.util.HashSet;
import java.util.Set;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestListener;
import com.serotonin.bacnet4j.util.RequestUtils;


class BacnetConn {
	
	private Node node;
	LocalDevice localDevice;
	private long defaultInterval;
	BacnetLink link;
	
	BacnetConn(BacnetLink link, Node node) {
		this.node = node;
		this.link = link;
	}
	
	void init() {
		
		String bip = node.getAttribute("broadcast ip").getString();
		int port = node.getAttribute("port").getNumber().intValue();
		String lba = node.getAttribute("local bind address").getString();
		int lnn = node.getAttribute("local network number").getNumber().intValue();
		int timeout = node.getAttribute("timeout").getNumber().intValue();
		int segtimeout = node.getAttribute("segment timeout").getNumber().intValue();
		int segwin = node.getAttribute("segment window").getNumber().intValue();
		int retries = node.getAttribute("retries").getNumber().intValue();
		int locdevId = node.getAttribute("local device id").getNumber().intValue();
		String locdevName = node.getAttribute("local device name").getString();
		String locdevVend = node.getAttribute("local device vendor").getString();
		defaultInterval = node.getAttribute("default refresh interval").getNumber().longValue();
		
		IpNetwork network = new IpNetwork(bip, port, lba, lnn);
        Transport transport = new Transport(network);
        transport.setTimeout(timeout);
        transport.setSegTimeout(segtimeout);
        transport.setSegWindow(segwin);
        transport.setRetries(retries);
        localDevice = new LocalDevice(locdevId, transport);
        try {
			localDevice.getConfiguration().setProperty(PropertyIdentifier.objectName, new CharacterString(locdevName));
			localDevice.getConfiguration().setProperty(PropertyIdentifier.vendorName, new CharacterString(locdevVend));
		} catch (BACnetServiceException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
        localDevice.setStrict(true);
        try {
        	
            localDevice.initialize();
            localDevice.sendGlobalBroadcast(localDevice.getIAm());
            //Thread.sleep(200000);
        } catch (Exception e) {
        	e.printStackTrace();
        } finally {
            //localDevice.terminate();
        }
        
        Action act = new Action(Permission.READ, new RemoveHandler());
        node.createChild("remove").setAction(act).build().setSerializable(false);
        
        act = new Action(Permission.READ, new DeviceDiscoveryHandler());
        node.createChild("discover devices").setAction(act).build().setSerializable(false);
        
        act = new Action(Permission.READ, new AddDeviceHandler());
        act.addParameter(new Parameter("MAC address", ValueType.STRING, new Value("10.0.1.248:47808")));
        act.addParameter(new Parameter("refresh interval", ValueType.NUMBER, new Value(defaultInterval)));
        act.addParameter(new Parameter("cov usage", ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED")));
        act.addParameter(new Parameter("cov lease time (minutes)", ValueType.NUMBER, new Value(60)));
        node.createChild("add device").setAction(act).build().setSerializable(false);
	}
	
	private class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			localDevice.terminate();
			node.clearChildren();
			node.getParent().removeChild(node);
		}
	}
	
	public enum CovType {NONE, UNCONFIREMD, CONFIRMED}
	
	private class AddDeviceHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			long interval = event.getParameter("refresh interval", ValueType.NUMBER).getNumber().longValue();
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife =event.getParameter("cov lease time (minutes)", ValueType.NUMBER).getNumber().intValue();
			HashSet<RemoteDevice> devs = new HashSet<RemoteDevice>();
			String mac = event.getParameter("MAC address", ValueType.STRING).getString();
			DiscoveryListener dl = new DiscoveryListener(devs);
			localDevice.getEventHandler().addListener(dl);
			try {
				localDevice.sendUnconfirmed(new Address(new OctetString(mac)), new WhoIsRequest());
			} catch (BACnetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				int waitlength = 0; 
				while (devs.size() < 1 && waitlength < 10000)  {
					try {
						waitlength += 100;
						Thread.sleep(100);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				localDevice.getEventHandler().removeListener(dl);
				setupDeviceNodes(devs, interval, covtype, covlife);
			}
			
		}
	}
	
	private class DeviceDiscoveryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			HashSet<RemoteDevice> devs = new HashSet<RemoteDevice>();
			DiscoveryListener dl = new DiscoveryListener(devs);
			localDevice.getEventHandler().addListener(dl);
			try {
				localDevice.sendGlobalBroadcast(new WhoIsRequest());
				Thread.sleep(5000);
			} catch (BACnetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				localDevice.getEventHandler().removeListener(dl);
				setupDeviceNodes(devs, defaultInterval, CovType.NONE, 60);
			}
		}
	}
	
	private void setupDeviceNodes(Set<RemoteDevice> devices, long interval, CovType covtype, int covlife) {
		for (RemoteDevice d: devices) {
			setupDeviceNode(d, interval, covtype, covlife);
		}
	}
	
	private void setupDeviceNode(final RemoteDevice d, long interval, CovType covtype, int covlife) {
		LocalDevice ld = localDevice;
        if (d== null || ld == null)
            return;

        try {
            RequestUtils.getProperties(ld, d, new RequestListener() {
                public boolean requestProgress(double progress, ObjectIdentifier oid,
                        PropertyIdentifier pid, UnsignedInteger pin, Encodable value) {
                    if (pid.equals(PropertyIdentifier.objectName))
                        d.setName(value.toString());
                    else if (pid.equals(PropertyIdentifier.vendorName))
                        d.setVendorName(value.toString());
                    else if (pid.equals(PropertyIdentifier.modelName))
                        d.setModelName(value.toString());
                    return false;
                }
            }, PropertyIdentifier.objectName, PropertyIdentifier.vendorName,
                    PropertyIdentifier.modelName);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println(d.getName());
        if (d.getName() != null) {
        	Node child = node.createChild(d.getName()).build();
        	child.setAttribute("refresh interval", new Value(interval));
        	child.setAttribute("cov usage", new Value(covtype.toString()));
        	child.setAttribute("cov lease time (minutes)", new Value(covlife));
        	new DeviceNode(getMe(), child, d);
        }
	}
	
	private static class DiscoveryListener extends DeviceEventAdapter {
		
		private Set<RemoteDevice> devices;
		
		DiscoveryListener(Set<RemoteDevice> devs) {
			devices = devs;
		}
		
		@Override
        public void iAmReceived(RemoteDevice d) {
                System.out.println("IAm received from " + d);
                //System.out.println("Segmentation: " + d.getSegmentationSupported());
                devices.add(d);
        }
	}
	
	private BacnetConn getMe() {
		return this;
	}
}
