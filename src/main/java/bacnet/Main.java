package bacnet;

import org.dsa.iot.dslink.DSLink;
import org.dsa.iot.dslink.DSLinkFactory;
import org.dsa.iot.dslink.DSLinkHandler;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.NodeManager;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.dsa.iot.historian.stats.GetHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.RemoteObject;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyMultipleRequest;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyRequest;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class Main extends DSLinkHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		DSLinkFactory.start(args, new Main());
	}

	@Override
	public boolean isResponder() {
		return true;
	}

	@Override
	public void onResponderInitialized(DSLink link) {
		LOGGER.info("Initialized");
		NodeManager manager = link.getNodeManager();
		Node superRoot = manager.getNode("/").getNode();
		BacnetLink.start(superRoot);

//		IpNetwork network = new IpNetworkBuilder().build();
//        Transport transport = new DefaultTransport(network);
//        //        transport.setTimeout(15000);
//        //        transport.setSegTimeout(15000);
//        LocalDevice localDevice = new LocalDevice(1234, transport);
//        //localDevice.getEventHandler().addListener(new BacnetListener(localDevice));
//        
//        try {
//			localDevice.initialize();
//			localDevice.sendGlobalBroadcast(new WhoIsRequest());
//			
//			RemoteDevice d = localDevice.getRemoteDeviceBlocking(1195);
//			localDevice.send(d, new ReadPropertyMultipleRequest())
//			RemoteObject ro = d.getObject(new ObjectIdentifier(ObjectType.analogInput, 100502));
//			String name = ro.getObjectName();
//			Encodable en = ro.getProperty(PropertyIdentifier.presentValue);
//			LOGGER.info(name + " : " + en.toString());
//			
//		} catch (Exception e) {
//			LOGGER.error("", e);
//		}
        
        
	}

	@Override
	public void onResponderConnected(DSLink link) {
		LOGGER.info("Connected");
	}

}
