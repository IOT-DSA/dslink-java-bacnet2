package bacnet;

import org.dsa.iot.dslink.node.Node;

import com.serotonin.bacnet4j.RemoteDevice;

public class DeviceNode extends DeviceFolder {
	
	RemoteDevice device;
	long interval;
	
	DeviceNode(BacnetConn conn, Node node, RemoteDevice d) {
		super(conn, node);
		this.device = d;
		this.root = this;
		
		this.interval = node.getAttribute("refresh interval").getNumber().longValue();
	}
	
}
