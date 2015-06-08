package bacnet;

import java.util.HashSet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.vertx.java.core.Handler;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestListener;
import com.serotonin.bacnet4j.util.RequestUtils;


class BacnetConn {
	
	private Node node;
	LocalDevice localDevice;
	HashSet<RemoteDevice> devices;
	
	BacnetConn(Node node) {
		this.node = node;
		devices = new HashSet<RemoteDevice>();
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
            //localDevice.sendUnconfirmed(new Address(new OctetString("10.0.1.248:47808")), new WhoIsRequest());
            localDevice.sendGlobalBroadcast(localDevice.getIAm());
            //Thread.sleep(200000);
        } catch (Exception e) {
        	e.printStackTrace();
        } finally {
            //localDevice.terminate();
        }
        
        Action act = new Action(Permission.READ, new DeviceDiscoveryHandler());
        node.createChild("discover devices").setAction(act).build().setSerializable(false);
	}
	
	private class DeviceDiscoveryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			DiscoveryListener dl = new DiscoveryListener();
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
				setupDeviceNodes();
			}
		}
	}
	
	private void setupDeviceNodes() {
		for (final RemoteDevice d: devices) {
			LocalDevice ld = localDevice;
            if (ld == null)
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
            	new DeviceNode(getMe(), child, d);
            }
		}
	}
	
	class DiscoveryListener extends DeviceEventAdapter {
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
