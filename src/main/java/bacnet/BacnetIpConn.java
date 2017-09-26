package bacnet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.util.BACnetUtils;

public class BacnetIpConn extends BacnetConn {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetIpConn.class);
	
	String subnetMask;
	int port;
	String localBindAddress;
	boolean isRegisteredAsForeignDevice;
	String bbmdIpList;

	BacnetIpConn(BacnetLink link, Node node) {
		super(link, node);
	}

	@Override
	Network getNetwork() {
		String subnetAddr = localBindAddress;
		if (subnetAddr.equals(IpNetwork.DEFAULT_BIND_IP)) {
			try {
				subnetAddr = InetAddress.getLocalHost().getHostAddress();
			} catch (UnknownHostException e) {
			}
		}
		return new IpNetworkBuilder().withSubnet(subnetAddr, getNetworkPrefixLength(subnetMask)).withPort(port).withLocalBindAddress(localBindAddress).withLocalNetworkNumber(localNetworkNumber).build();
	}
	
	int getNetworkPrefixLength(String subnetMask) {
		long addr = IpNetworkUtils.bytesToLong(BACnetUtils.dottedStringToBytes(subnetMask));
		int shift = 32;
		for (int i = 0; i < 32 ; i++) {
			shift--;
			if ((addr & (1L << shift)) == 0) {
				return i;
			}
		}
		return 32;
	}
	
	void parseBroadcastManagementDevice() {
		int nextNeworkNumber = localNetworkNumber + 1;
		for (String entry : bbmdIpList.split(",")) {
			entry = entry.trim();
			if (!entry.isEmpty()) {
				String[] splitEntry = entry.split(":");
				String bbmdIp = splitEntry.length > 0 ? splitEntry[0] : "";
				String bbmdPortStr = splitEntry.length > 1 ? splitEntry[1]: "";
				String networkNumberStr = splitEntry.length > 2 ? splitEntry[2]: "";
				
				int bbmdPort = bbmdPortStr.matches("\\d+") ? Integer.parseInt(bbmdPortStr) : IpNetwork.DEFAULT_PORT;
				int networkNumber = networkNumberStr.matches("\\d+") ? Integer.parseInt(networkNumberStr) : nextNeworkNumber;
				nextNeworkNumber = networkNumber + 1;
				if (!bbmdIp.isEmpty()) {
					bbmdIpToPort.put(bbmdIp, bbmdPort);
					OctetString os = IpNetworkUtils.toOctetString(bbmdIp, bbmdPort);
					networkRouters.put(networkNumber, os);
				}
			}
		}
	}
	
	@Override
	void registerAsForeignDevice(Transport transport) {
		Network network = transport.getNetwork();
		if (!isRegisteredAsForeignDevice)
			return;
		
		for (Map.Entry<Integer, OctetString> entry : networkRouters.entrySet()) {
			Integer networkNumber = entry.getKey();
			OctetString linkService = entry.getValue();
			transport.addNetworkRouter(networkNumber, linkService);
		}

		for (Map.Entry<String, Integer> entry : bbmdIpToPort.entrySet()) {
			String bbmdIp = entry.getKey();
			Integer bbmdPort = entry.getValue();
			try {
				((IpNetwork) network).registerAsForeignDevice(
						new InetSocketAddress(InetAddress.getByName(bbmdIp), bbmdPort), 100);
			} catch (UnknownHostException e) {
				LOGGER.debug("", e);
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}

	}
	
	@Override
	protected void makeEditAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				edit(event);
			}
		});
		act.addParameter(new Parameter("Subnet Mask", ValueType.STRING, new Value(subnetMask)));
		act.addParameter(new Parameter("Port", ValueType.NUMBER, new Value(port)));
		act.addParameter(new Parameter("Local Bind Address", ValueType.STRING, new Value(localBindAddress)));
		act.addParameter(new Parameter("Local Network Number", ValueType.NUMBER, new Value(localNetworkNumber)));
		act.addParameter(new Parameter("Register As Foreign Device In BBMD", ValueType.BOOL, new Value(isRegisteredAsForeignDevice)));
		act.addParameter(new Parameter("BBMD IPs With Network Number", ValueType.STRING, new Value(bbmdIpList)));
//		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL, new Value()));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, new Value(timeout)));
		act.addParameter(new Parameter("Segment Timeout", ValueType.NUMBER, new Value(segmentTimeout)));
		act.addParameter(new Parameter("Segment Window", ValueType.NUMBER, new Value(segmentWindow)));
		act.addParameter(new Parameter("Retries", ValueType.NUMBER, new Value(retries)));
		act.addParameter(new Parameter("Local Device ID", ValueType.NUMBER, new Value(localDeviceId)));
		act.addParameter(new Parameter("Local Device Name", ValueType.STRING, new Value(localDeviceName)));
		act.addParameter(new Parameter("Local Device Vendor", ValueType.STRING, new Value(localDeviceVendor)));
		
		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null) {
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	@Override
	protected void setVarsFromConfigs() {
		super.setVarsFromConfigs();
		subnetMask = Utils.safeGetRoConfigString(node, "Subnet Mask", subnetMask);
		port = Utils.safeGetRoConfigNum(node, "Port", port).intValue();
		localBindAddress = Utils.safeGetRoConfigString(node, "Local Bind Address", localBindAddress);
		isRegisteredAsForeignDevice = Utils.safeGetRoConfigBool(node, "Register As Foreign Device In BBMD", isRegisteredAsForeignDevice);
		bbmdIpList = Utils.safeGetRoConfigString(node, "BBMD IPs With Network Number", bbmdIpList);
		parseBroadcastManagementDevice();
	}
	
}
