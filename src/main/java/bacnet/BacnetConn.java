package bacnet;

import java.util.ArrayList;
import java.util.List;
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
        
        act = new Action(Permission.READ, new EditHandler());
        act.addParameter(new Parameter("broadcast ip", ValueType.STRING, node.getAttribute("broadcast ip")));
		act.addParameter(new Parameter("port", ValueType.NUMBER, node.getAttribute("port")));
		act.addParameter(new Parameter("local bind address", ValueType.STRING, node.getAttribute("local bind address")));
		act.addParameter(new Parameter("local network number", ValueType.NUMBER, node.getAttribute("local network number")));
		act.addParameter(new Parameter("timeout", ValueType.NUMBER, node.getAttribute("timeout")));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, node.getAttribute("segment timeout")));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, node.getAttribute("segment window")));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, node.getAttribute("local device id")));
		act.addParameter(new Parameter("local device name", ValueType.STRING, node.getAttribute("local device name")));
		act.addParameter(new Parameter("local device vendor", ValueType.STRING, node.getAttribute("local device vendor")));
		act.addParameter(new Parameter("default refresh interval", ValueType.NUMBER, node.getAttribute("default refresh interval")));
		node.createChild("edit").setAction(act).build().setSerializable(false);
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String bip = event.getParameter("broadcast ip", ValueType.STRING).getString();
			int port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
			String lba = event.getParameter("local bind address", ValueType.STRING).getString();
			int lnn = event.getParameter("local network number", ValueType.NUMBER).getNumber().intValue();
			int timeout = event.getParameter("timeout", ValueType.NUMBER).getNumber().intValue();
			int segtimeout = event.getParameter("segment timeout", ValueType.NUMBER).getNumber().intValue();
			int segwin = event.getParameter("segment window", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int locdevId = event.getParameter("local device id", ValueType.NUMBER).getNumber().intValue();
			String locdevName = event.getParameter("local device name", ValueType.STRING).getString();
			String locdevVend = event.getParameter("local device vendor", ValueType.STRING).getString();
			long interval = event.getParameter("default refresh interval", ValueType.NUMBER).getNumber().longValue();
			
			node.setAttribute("broadcast ip", new Value(bip));
			node.setAttribute("port", new Value(port));
			node.setAttribute("local bind address", new Value(lba));
			node.setAttribute("local network number", new Value(lnn));
			node.setAttribute("timeout", new Value(timeout));
			node.setAttribute("segment timeout", new Value(segtimeout));
			node.setAttribute("segment window", new Value(segwin));
			node.setAttribute("retries", new Value(retries));
			node.setAttribute("local device id", new Value(locdevId));
			node.setAttribute("local device name", new Value(locdevName));
			node.setAttribute("local device vendor", new Value(locdevVend));
			node.setAttribute("default refresh interval", new Value(interval));
			
			localDevice.terminate();
			if (node.getChildren()!=null) {
				for (Node child: node.getChildren().values()) {
					if (child.getAction()!=null) node.removeChild(child);
				}
			}
			init();
		}
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
			String mac = event.getParameter("MAC address", ValueType.STRING).getString();
			
			List<RemoteDevice> devs = getDevice(mac, interval, covtype, covlife);
			
			setupDeviceNodes(devs, interval, covtype, covlife);
		}
	}
	
	List<RemoteDevice> getDevice(String mac, long interval, CovType covtype, int covlife) {
		ArrayList<RemoteDevice> devs = new ArrayList<RemoteDevice>();
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
		}
		return devs;
	}
	
	private class DeviceDiscoveryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			ArrayList<RemoteDevice> devs = new ArrayList<RemoteDevice>();
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
	
	private void setupDeviceNodes(List<RemoteDevice> devices, long interval, CovType covtype, int covlife) {
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
        	child.setAttribute("MAC address", new Value(d.getAddress().getMacAddress().toIpPortString()));
        	child.setAttribute("refresh interval", new Value(interval));
        	child.setAttribute("cov usage", new Value(covtype.toString()));
        	child.setAttribute("cov lease time (minutes)", new Value(covlife));
        	new DeviceNode(getMe(), child, d);
        }
	}
	
	private static class DiscoveryListener extends DeviceEventAdapter {
		
		private List<RemoteDevice> devices;
		
		DiscoveryListener(List<RemoteDevice> devs) {
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
