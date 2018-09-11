package bacnet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
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
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.util.BACnetUtils;

public class BacnetIpConn extends BacnetConn {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetIpConn.class);
	
	static final String ACTION_ADD_ROUTER = "add router";
	
	String subnetMask;
	int port;
	String localBindAddress;
	boolean useWildcard;
	boolean isRegisteredAsForeignDevice;
	String bbmdIpList;
	Node routersNode;
	
	private int lastRouter = 0;

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
		IpNetworkBuilder networkBuilder = new IpNetworkBuilder()
				.withSubnet(subnetAddr, getNetworkPrefixLength(subnetMask))
				.withPort(port)
				.withLocalNetworkNumber(localNetworkNumber);
		if (!useWildcard) {
			networkBuilder.withLocalBindAddress(localBindAddress);
		}
		return networkBuilder.build();
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
	
	void initializeRoutersNode() {
		routersNode = node.getChild(NODE_ROUTERS, true);
		if (routersNode == null) {
			routersNode = node.createChild(NODE_ROUTERS, true).build();
		}
		if (routersNode.getChildren() != null) {
			for (Node child: routersNode.getChildren().values()) {
				Value ip = child.getRoConfig("IP");
				Value port = child.getRoConfig("Port");
				Value netnum = child.getRoConfig("Network Number");
				Value register = child.getRoConfig("Register as Foreign Device");
				String[] splname = child.getName().split("-");
				int num = -1;
				if (splname.length == 2) {
					try {
						num = Integer.parseInt(splname[1]);
					} catch (NumberFormatException e) {}
				}
				if (ip != null && port != null && netnum != null && register != null && num >= 0) {
					new BacnetRouter(this, child);
					if (num > lastRouter) {
						num = lastRouter;
					}
				} else if (child.getAction() == null) {
					child.delete(false);
				}
			}
		}
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
				addRouterNode(networkNumber, bbmdIp, bbmdPort, isRegisteredAsForeignDevice);
			}
		}
		
		makeRouterAddAction();
	}
	
	@Override
	void setupRouters() {		
		if (routersNode.getChildren() == null) {
			return;
		}
		for (Node router: routersNode.getChildren().values()) {
			if (router.getAction() == null) {
				addRouter(router);
			}
		}
	}
	
	void addRouter(Node router) {
		Network network = transport.getNetwork();
		
		int networkNumber = router.getRoConfig("Network Number").getNumber().intValue();
		String routerIp = router.getRoConfig("IP").getString();
		int routerPort = router.getRoConfig("Port").getNumber().intValue();
		boolean shouldRegister = router.getRoConfig("Register as Foreign Device").getBool();
		
		OctetString linkService = IpNetworkUtils.toOctetString(routerIp, routerPort);
		transport.addNetworkRouter(networkNumber, linkService);
		
		if (shouldRegister) {
			try {
				((IpNetwork) network).registerAsForeignDevice(
						new InetSocketAddress(InetAddress.getByName(routerIp), routerPort), 100);
			} catch (UnknownHostException e) {
				LOGGER.debug("", e);
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}
	}
	
	void removeRouter(Node router) {		
		int networkNumber = router.getRoConfig("Network Number").getNumber().intValue();
		String routerIp = router.getRoConfig("IP").getString();
		int routerPort = router.getRoConfig("Port").getNumber().intValue();
		
		OctetString linkService = IpNetworkUtils.toOctetString(routerIp, routerPort);
		transport.getNetworkRouters().remove(networkNumber, linkService);
	}
	
	private void makeRouterAddAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				addRouterNode(event);
			}
		});
		act.addParameter(new Parameter("Network Number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("IP", ValueType.STRING));
		act.addParameter(new Parameter("Port", ValueType.NUMBER, new Value(IpNetwork.DEFAULT_PORT)));
		act.addParameter(new Parameter("Register as Foreign Device", ValueType.BOOL));
		
		Node anode = routersNode.getChild(ACTION_ADD_ROUTER, true);
		if (anode == null) {
			routersNode.createChild(ACTION_ADD_ROUTER, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void addRouterNode(ActionResult event) {
		int networkNumber = event.getParameter("Network Number", ValueType.NUMBER).getNumber().intValue();
		String routerIp = event.getParameter("IP", ValueType.STRING).getString();
		int routerPort = event.getParameter("Port", ValueType.NUMBER).getNumber().intValue();
		boolean shouldRegister = event.getParameter("Register as Foreign Device", ValueType.BOOL).getBool();
		addRouterNode(networkNumber, routerIp, routerPort, shouldRegister);
	}
	
	private void addRouterNode(int networkNumber, String routerIp, int routerPort, boolean shouldRegister) {
		if (!routerIp.isEmpty()) {
			String name = "router-" + lastRouter;
			lastRouter += 1;
			Node child = routersNode.createChild(name, true).build();
			child.setRoConfig("Network Number", new Value(networkNumber));
			child.setRoConfig("IP", new Value(routerIp));
			child.setRoConfig("Port", new Value(routerPort));
			child.setRoConfig("Register as Foreign Device", new Value(shouldRegister));
			new BacnetRouter(this, child);
			if (transport != null) {
				addRouter(child);
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
		act.addParameter(new Parameter("Use Wildcard Address for Binding", ValueType.BOOL, new Value(useWildcard)));
		act.addParameter(new Parameter("Local Network Number", ValueType.NUMBER, new Value(localNetworkNumber)));
//		act.addParameter(new Parameter("Register As Foreign Device In BBMD", ValueType.BOOL, new Value(isRegisteredAsForeignDevice)));
//		act.addParameter(new Parameter("BBMD IPs With Network Number", ValueType.STRING, new Value(bbmdIpList)));
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
		useWildcard = Utils.safeGetRoConfigBool(node, "Use Wildcard Address for Binding", useWildcard);
//		isRegisteredAsForeignDevice = Utils.safeGetRoConfigBool(node, "Register As Foreign Device In BBMD", isRegisteredAsForeignDevice);
//		bbmdIpList = Utils.safeGetRoConfigString(node, "BBMD IPs With Network Number", bbmdIpList);
//		parseRouterListConfig();
	}
	
}
