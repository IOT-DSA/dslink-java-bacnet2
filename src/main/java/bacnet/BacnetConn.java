package bacnet;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.StringUtils;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.mstp.MasterNode;
import com.serotonin.bacnet4j.npdu.mstp.MstpNetwork;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.BACnetError;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestListener;
import com.serotonin.bacnet4j.util.RequestUtils;
import com.serotonin.io.serial.SerialParameters;


class BacnetConn {
	private static final Logger LOGGER;
	
	Node node;
	private final Node statnode;
	LocalDevice localDevice;
	private long defaultInterval;
	BacnetLink link;
	boolean isIP;
	private int unnamedCount;
	
	private final ScheduledThreadPoolExecutor stpe;
	
	static {
		LOGGER = LoggerFactory.getLogger(BacnetConn.class);
	}
	
	BacnetConn(BacnetLink link, Node node) {
		this.node = node;
		this.link = link;
		
		isIP = node.getAttribute("isIP").getBool();
		defaultInterval = node.getAttribute("default polling interval").getNumber().longValue();
		
		if (!isIP) {
			link.serialConns.add(this);
			stpe = Objects.createDaemonThreadPool();
		} else {
			stpe = null;
		}
		this.statnode = node.createChild("STATUS").setValueType(ValueType.STRING).setValue(new Value("")).build();
		this.statnode.setSerializable(false);
	
		unnamedCount = 0;
	}
	
	ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return stpe;
	}
	
	void init() {
		statnode.setValue(new Value("Setting up connection"));
		
		isIP = node.getAttribute("isIP").getBool();
		String bip = node.getAttribute("broadcast ip").getString();
		int port = node.getAttribute("port").getNumber().intValue();
		String lba = node.getAttribute("local bind address").getString();
		String commPort = node.getAttribute("comm port id").getString();
		int baud = node.getAttribute("baud rate").getNumber().intValue();
		int station = node.getAttribute("this station id").getNumber().intValue();
		int ferc = node.getAttribute("frame error retry count").getNumber().intValue();
		int lnn = node.getAttribute("local network number").getNumber().intValue();
		boolean strict = node.getAttribute("strict device comparisons").getBool();
		int timeout = node.getAttribute("Timeout").getNumber().intValue();
		int segtimeout = node.getAttribute("segment timeout").getNumber().intValue();
		int segwin = node.getAttribute("segment window").getNumber().intValue();
		int retries = node.getAttribute("retries").getNumber().intValue();
		int locdevId = node.getAttribute("local device id").getNumber().intValue();
		String locdevName = node.getAttribute("local device name").getString();
		String locdevVend = node.getAttribute("local device vendor").getString();
		defaultInterval = node.getAttribute("default polling interval").getNumber().longValue();
		
		Action act = new Action(Permission.READ, new RemoveHandler());
		Node anode = node.getChild("remove");
        if (anode == null) node.createChild("remove").setAction(act).build().setSerializable(false);
        else anode.setAction(act);
        
        act = getEditAction();
		anode = node.getChild("edit");
		if (anode == null) {
			anode = node.createChild("edit").setAction(act).build();
			anode.setSerializable(false);
		} else {
			anode.setAction(act);
		}
		
		Network network;
		if (isIP) {
			network = new IpNetwork(bip, port, lba, lnn);
		} else {
			SerialParameters params = new SerialParameters();
	        params.setCommPortId(commPort);
	        params.setBaudRate(baud);
	        params.setPortOwnerName("DSLink");
	        
	        MasterNode mastnode = new MasterNode(params, (byte) station, ferc);
	        network = new MstpNetwork(mastnode, lnn);
		}
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
			LOGGER.debug("error: ", e1);
		}
        localDevice.setStrict(strict);
        try {
        	
            localDevice.initialize();
            localDevice.sendGlobalBroadcast(localDevice.getIAm());
            //Thread.sleep(200000);
        } catch (Exception e) {
        	//e.printStackTrace();
        	//remove();
        	LOGGER.debug("error: ", e);
        	statnode.setValue(new Value("Error initializing local device"));
        	localDevice.terminate();
        	localDevice = null;
        } finally {
            //localDevice.terminate();
        }
        
		if (localDevice != null) {
			act = new Action(Permission.READ, new StopHandler());
			anode = node.getChild("stop");
			if (anode == null) node.createChild("stop").setAction(act).build().setSerializable(false);
			else anode.setAction(act);
		}
		
		act = new Action(Permission.READ, new RestartHandler());
		anode = node.getChild("restart");
		if (anode == null) node.createChild("restart").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
        
        if (localDevice != null) {
			act = new Action(Permission.READ, new DeviceDiscoveryHandler());
			anode = node.getChild("discover devices");
			if (anode == null) node.createChild("discover devices").setAction(act).build().setSerializable(false);
			else anode.setAction(act);
			
			act = new Action(Permission.READ, new AddDeviceHandler());
			act.addParameter(new Parameter("name", ValueType.STRING));
			String defMac = "10";
			if (isIP) defMac = "10.0.1.248:47808";
			act.addParameter(new Parameter("MAC address", ValueType.STRING, new Value(defMac)));
			act.addParameter(new Parameter("instance number",ValueType.NUMBER));
			act.addParameter(new Parameter("network number", ValueType.NUMBER, new Value(0)));
			act.addParameter(new Parameter("link service MAC", ValueType.STRING));
			act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(((double) defaultInterval) / 1000)));
			act.addParameter(new Parameter("cov usage", ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED")));
			act.addParameter(new Parameter("cov lease time (minutes)",ValueType.NUMBER, new Value(60)));
			anode = node.getChild("add device");
			if (anode == null)node.createChild("add device").setAction(act).build().setSerializable(false);
			else anode.setAction(act);
			statnode.setValue(new Value("Connected"));

		}
		
	}
	
	Action getEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
        act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
        if (isIP) {
			act.addParameter(new Parameter("broadcast ip", ValueType.STRING, node.getAttribute("broadcast ip")));
			act.addParameter(new Parameter("port", ValueType.NUMBER, node.getAttribute("port")));
			act.addParameter(new Parameter("local bind address", ValueType.STRING, node.getAttribute("local bind address")));
		} else {
			Set<String> portids = BacnetLink.listPorts();
			if (portids.size() > 0) {
				 if (portids.contains(node.getAttribute("comm port id").getString())) {
					 act.addParameter(new Parameter("comm port id", ValueType.makeEnum(portids), node.getAttribute("comm port id")));
					 act.addParameter(new Parameter("comm port id (manual entry)", ValueType.STRING));
				 } else {
					 act.addParameter(new Parameter("comm port id", ValueType.makeEnum(portids)));
					 act.addParameter(new Parameter("comm port id (manual entry)", ValueType.STRING, node.getAttribute("comm port id")));
				 }
			} else {
				act.addParameter(new Parameter("comm port id", ValueType.STRING, node.getAttribute("comm port id")));
			}
			act.addParameter(new Parameter("baud rate", ValueType.NUMBER, node.getAttribute("baud rate")));
			act.addParameter(new Parameter("this station id", ValueType.NUMBER, node.getAttribute("this station id")));
			act.addParameter(new Parameter("frame error retry count", ValueType.NUMBER, node.getAttribute("frame error retry count")));
		}
		act.addParameter(new Parameter("local network number", ValueType.NUMBER, node.getAttribute("local network number")));
		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, node.getAttribute("strict device comparisons")));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, node.getAttribute("Timeout")));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, node.getAttribute("segment timeout")));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, node.getAttribute("segment window")));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, node.getAttribute("local device id")));
		act.addParameter(new Parameter("local device name", ValueType.STRING, node.getAttribute("local device name")));
		act.addParameter(new Parameter("local device vendor", ValueType.STRING, node.getAttribute("local device vendor")));
		double defint = node.getAttribute("default polling interval").getNumber().doubleValue()/1000;
		act.addParameter(new Parameter("default polling interval", ValueType.NUMBER, new Value(defint)));
		return act;
	}
	
	private class StopHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			stop();
		}
	}
	
	private class RestartHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			stop();
			restoreLastSession();
		}
	}
	
	void stop() {
		if (localDevice!=null) {
			localDevice.terminate();
			localDevice = null;
			node.removeChild("stop");
			node.removeChild("discover devices");
			node.removeChild("add device");
			statnode.setValue(new Value("Stopped"));
		}
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			if (isIP) {
				String bip = event.getParameter("broadcast ip", ValueType.STRING).getString();
				int port = event.getParameter("port", ValueType.NUMBER).getNumber().intValue();
				String lba = event.getParameter("local bind address", ValueType.STRING).getString();
				node.setAttribute("broadcast ip", new Value(bip));
				node.setAttribute("port", new Value(port));
				node.setAttribute("local bind address", new Value(lba));
			} else {
				String commPort = event.getParameter("comm port id", ValueType.STRING).getString();
				int baud = event.getParameter("baud rate", ValueType.NUMBER).getNumber().intValue();
				int station = event.getParameter("this station id", ValueType.NUMBER).getNumber().intValue();
				int ferc = event.getParameter("frame error retry count", ValueType.NUMBER).getNumber().intValue();
				node.setAttribute("comm port id", new Value(commPort));
				node.setAttribute("baud rate", new Value(baud));
				node.setAttribute("this station id", new Value(station));
				node.setAttribute("frame error retry count", new Value(ferc));
			}
			int lnn = event.getParameter("local network number", ValueType.NUMBER).getNumber().intValue();
			boolean strict = event.getParameter("strict device comparisons", ValueType.BOOL).getBool();
			int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
			int segtimeout = event.getParameter("segment timeout", ValueType.NUMBER).getNumber().intValue();
			int segwin = event.getParameter("segment window", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int locdevId = event.getParameter("local device id", ValueType.NUMBER).getNumber().intValue();
			String locdevName = event.getParameter("local device name", ValueType.STRING).getString();
			String locdevVend = event.getParameter("local device vendor", ValueType.STRING).getString();
			long interval = (long) (1000*event.getParameter("default polling interval", ValueType.NUMBER).getNumber().doubleValue());
			
			node.setAttribute("local network number", new Value(lnn));
			node.setAttribute("strict device comparisons", new Value(strict));
			node.setAttribute("Timeout", new Value(timeout));
			node.setAttribute("segment timeout", new Value(segtimeout));
			node.setAttribute("segment window", new Value(segwin));
			node.setAttribute("retries", new Value(retries));
			node.setAttribute("local device id", new Value(locdevId));
			node.setAttribute("local device name", new Value(locdevName));
			node.setAttribute("local device vendor", new Value(locdevVend));
			node.setAttribute("default polling interval", new Value(interval));

			stop();
			
			if (!name.equals(node.getName())) {
				rename(name);
			}
			
//			if (node.getChildren()!=null) {
//				for (Node child: node.getChildren().values()) {
//					if (child.getAction()!=null) node.removeChild(child);
//				}
//			}
			restoreLastSession();
		}
	}
	
	private class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}
	
	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName())) duplicate(newname);
		}
	}
	
	private void remove() {
		stop();
		node.clearChildren();
		link.serialConns.remove(getMe());
		node.getParent().removeChild(node);
		if (!isIP) stpe.shutdown();
	}
	
	protected void rename(String name) {
		duplicate(name);
		remove();
	}
	
	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject nodeobj = jobj.get(node.getName());
		jobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		BacnetConn bc = new BacnetConn(link, newnode);
		bc.restoreLastSession();
	}
	
	
	public enum CovType {NONE, UNCONFIRMED, CONFIRMED}
	
	private class AddDeviceHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = null;
			Value namev = event.getParameter("name", ValueType.STRING);
			if (namev != null) name = namev.getString();
			long interval = (long) (1000*event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue());
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife = event.getParameter("cov lease time (minutes)", ValueType.NUMBER).getNumber().intValue();
			String mac = event.getParameter("MAC address", new Value("")).getString();
			int instanceNum = event.getParameter("instance number", new Value(-1)).getNumber().intValue();
			int netNum = event.getParameter("network number", ValueType.NUMBER).getNumber().intValue();
			String linkMac = event.getParameter("link service MAC", new Value("")).getString();
			
			RemoteDevice dev = getDevice(mac, instanceNum, netNum, linkMac, interval, covtype, covlife);
			
			setupDeviceNode(dev, null, name, mac, instanceNum, netNum, linkMac, interval, covtype, covlife);
		}
	}
	
	RemoteDevice getDevice(String mac, int instanceNum, int netNum, String linkMac,  long interval, CovType covtype, int covlife) {
		ConcurrentLinkedQueue<RemoteDevice> devs = new ConcurrentLinkedQueue<RemoteDevice>();
		DiscoveryListener dl = new DiscoveryListener(devs);
		if (localDevice == null) {
			stop();
			return null;
		}
		if (!mac.isEmpty() && instanceNum>=0) {
            Address address = new Address(netNum, mac);
            OctetString linkService = null;
            if (!linkMac.isEmpty()) linkService = new OctetString(linkMac);
            try {
				return localDevice.findRemoteDevice(address, linkService, instanceNum);
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}
		localDevice.getEventHandler().addListener(dl);
		try {
			if (mac.isEmpty()) localDevice.sendGlobalBroadcast(new WhoIsRequest(new UnsignedInteger(instanceNum), new UnsignedInteger(instanceNum)));
			else if (instanceNum<0) localDevice.sendUnconfirmed(new Address(netNum, mac), new WhoIsRequest());
			else localDevice.sendUnconfirmed(new Address(netNum, mac), new WhoIsRequest(new UnsignedInteger(instanceNum), new UnsignedInteger(instanceNum)));
		} catch (BACnetException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
			LOGGER.debug("error: ", e);
		} catch (Exception e1) {
			LOGGER.debug("error: ", e1);
		} finally {
			int totaltime = 0;
			int waittime = 500; 
			while (devs.size() < 1 && totaltime < 10000)  {
				try {
					totaltime += waittime;
					Thread.sleep(waittime);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					//e.printStackTrace();
					LOGGER.debug("error: ", e);
				}
			}
			localDevice.getEventHandler().removeListener(dl);
		}
		return devs.poll();
	}
	
	private class DeviceDiscoveryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			ConcurrentLinkedQueue<RemoteDevice> devs = new ConcurrentLinkedQueue<RemoteDevice>();
			DiscoveryListener dl = new DiscoveryListener(devs);
			if (localDevice == null) {
				stop();
				return;
			}
			localDevice.getEventHandler().addListener(dl);
			try {
				localDevice.sendGlobalBroadcast(new WhoIsRequest());
				int totaltime = 0;
				int waittime = 500;
				while (totaltime <= 15000 || devs.size() > 0) {
					Thread.sleep(waittime);
					totaltime += waittime;
					RemoteDevice d = devs.poll();
					if (d != null && !devInTree(d)) {
						setupDeviceNode(d, null, null, null, null, null, null, defaultInterval, CovType.NONE, 60);
					}
				}
			} catch (BACnetException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
				LOGGER.error("error: ", e);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
				LOGGER.error("error: ", e);
			} finally {
				localDevice.getEventHandler().removeListener(dl);
			}
		}
	}
	
	private boolean devInTree(RemoteDevice d) {
		String mac;
    	try {
    		mac = d.getAddress().getMacAddress().toIpPortString();
    	} catch (Exception e) {
    		mac = Byte.toString(d.getAddress().getMacAddress().getMstpAddress());
    	}
    	if (node.getChildren()==null) return false;
    	for (Node child: node.getChildren().values()) {
    		if (new Value(mac).equals(child.getAttribute("MAC address"))) return true;
    	}
    	return false;
	}
	
//	private void setupDeviceNodes(List<RemoteDevice> devices) {
//		for (RemoteDevice d: devices) {
//			setupDeviceNode(d, null, defaultInterval, CovType.NONE, 60);
//		}
//	}
	
	void getDeviceProps(final RemoteDevice d) {
		LocalDevice ld = localDevice;
        if (d== null || ld == null)
            return;

        try {
            RequestUtils.getProperties(ld, d, new RequestListener() {
                public boolean requestProgress(double progress, ObjectIdentifier oid,
                        PropertyIdentifier pid, UnsignedInteger pin, Encodable value) {
                    if (pid.equals(PropertyIdentifier.objectName)) {
                    	String name = toLegalName(value.toString());
                    	if (value instanceof BACnetError || name.length() < 1) {
                    		d.setName("unnamed device " + unnamedCount);
                    		unnamedCount += 1;
                    	} else {
                    		d.setName(name);
                    	}
                    }
//                    else if (pid.equals(PropertyIdentifier.vendorName))
//                        d.setVendorName(value.toString());
//                    else if (pid.equals(PropertyIdentifier.modelName))
//                        d.setModelName(value.toString());
                    return false;
                }
            }, PropertyIdentifier.objectName);
        }
        catch (Exception e) {
           // e.printStackTrace();
        	LOGGER.debug("error: ", e);
        }
        LOGGER.debug("Got device name: "  + d.getName());
	}
	
	static String toLegalName(String s) {
		if (s == null) return "";
//		while (s.length() > 0 && (s.startsWith("$") || s.startsWith("@"))) {
//			s = s.substring(1);
//		}
//		s = s.replace('%', ' ');
//		s = s.replace('.', ' ');
//		s = s.replace('/', ' ');
//		s = s.replace('\\', ' ');
//		s = s.replace('?', ' ');
//		s = s.replace('*', ' ');
//		s = s.replace(':', ' ');
//		s = s.replace('|', ' ');
//		s = s.replace('"', ' ');
//		s = s.replace('<', ' ');
//		s = s.replace('>', ' ');
		return StringUtils.filterBannedChars(s);
	}

	private DeviceNode setupDeviceNode(final RemoteDevice d, Node child, String name, String mac, Integer instanceNum, Integer netNum, String linkMac, long interval, CovType covtype, int covlife) {
		if (d != null) getDeviceProps(d);
		if (name == null && d != null) name = d.getName();
		if (linkMac == null) linkMac = "";
		if (mac == null) mac = "";
        if (name != null) {
        	if (child == null) {
				String modname = name;
				child = node.getChild(modname);
				int i = 1;
				while (child != null) {
					i++;
					modname = name + i;
					child = node.getChild(modname);
				}
				child = node.createChild(modname).build();
			}
        	if (d != null) {
				try {
					mac = d.getAddress().getMacAddress().toIpPortString();
				} catch (Exception e) {
					mac = Byte.toString(d.getAddress().getMacAddress().getMstpAddress());
				}
				try {
					linkMac = d.getLinkService().getMacAddressDottedString();
				} catch (Exception e) {
					linkMac = "";
				}
				instanceNum = d.getInstanceNumber();
				netNum = d.getAddress().getNetworkNumber().intValue();
			}
			child.setAttribute("MAC address", new Value(mac));
			child.setAttribute("instance number", new Value(instanceNum));
			child.setAttribute("network number", new Value(netNum));
			child.setAttribute("link service MAC", new Value(linkMac));
        	child.setAttribute("polling interval", new Value(interval));
        	child.setAttribute("cov usage", new Value(covtype.toString()));
        	child.setAttribute("cov lease time (minutes)", new Value(covlife));
        	return new DeviceNode(getMe(), child, d);
        }
        return null;
	}
	
	private static class DiscoveryListener extends DeviceEventAdapter {
		
		private Queue<RemoteDevice> devices;
		
		DiscoveryListener(Queue<RemoteDevice> devs) {
			devices = devs;
		}
		
		@Override
        public void iAmReceived(RemoteDevice d) {
                LOGGER.info("IAm received from " + d);
                devices.add(d);
        }
	}
	
	private BacnetConn getMe() {
		return this;
	}

	public void restoreLastSession() {
		init();
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			restoreDevice(child);
		}
	}
	
	void restoreDevice(Node child) {
		Value mac = child.getAttribute("MAC address");
		Value instanceNum = child.getAttribute("instance number");
		Value netNum = child.getAttribute("network number");
		Value linkMac = child.getAttribute("link service MAC");
		Value refint = child.getAttribute("polling interval");
		Value covtype = child.getAttribute("cov usage");
		Value covlife = child.getAttribute("cov lease time (minutes)");
		if (mac!=null && instanceNum!=null && netNum!=null && linkMac!=null && refint!=null && covtype!=null && covlife!=null) {
			CovType ct = CovType.NONE;
			try {
				ct = CovType.valueOf(covtype.getString());
			} catch (Exception e) {
			}
			
			boolean disabled = child.getChild("STATUS") != null && new Value("disabled").equals(child.getChild("STATUS").getValue());
			DeviceNode dn = null;
			if (!disabled) {
				RemoteDevice dev = getDevice(mac.getString(), instanceNum.getNumber().intValue(), netNum.getNumber().intValue(), linkMac.getString(), refint.getNumber().longValue(), ct, covlife.getNumber().intValue());
				dn = setupDeviceNode(dev, child, child.getName(), mac.getString(), instanceNum.getNumber().intValue(), netNum.getNumber().intValue(), linkMac.getString(), refint.getNumber().longValue(), ct, covlife.getNumber().intValue());
			} else {
				dn = setupDeviceNode(null, child, child.getName(), mac.getString(), instanceNum.getNumber().intValue(), netNum.getNumber().intValue(), linkMac.getString(), refint.getNumber().longValue(), ct, covlife.getNumber().intValue());
			}
				
			if (dn!=null) dn.restoreLastSession();
			else node.removeChild(child);
		} else if (child.getAction() == null && !child.getName().equals("STATUS")) {
			node.removeChild(child);
		}
	}
}
