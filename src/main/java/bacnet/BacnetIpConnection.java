package bacnet;

import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BacnetIpConnection extends BacnetConn {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(BacnetConn.class);
	}

	static final String ATTR_BROADCAST_IP = "broadcast ip";
	static final String ATTR_PORT = "port";
	static final String ATTR_LOCAL_BIND_ADDRESS = "local bind address";
	static final String ATTR_REGISTER_AS_FOREIGN_DEVICE = "register as foreign device in bbmd";
	static final String ATTR_BBMD_IP_WITH_NETWORK_NUMBER = "bbmd ips with network number";
	static final int DEFAULT_TIME_TO_LIVE = 100;

	String broadcastIp;
	int port;
	String localBindAddress;
	boolean isRegisteredAsForegnDevice;
	String bbmdIpList;

	BacnetIpConnection(BacnetLink link, Node node) {
		super(link, node);

		broadcastIp = node.getAttribute(ATTR_BROADCAST_IP).getString();
		port = node.getAttribute(ATTR_PORT).getNumber().intValue();
		localBindAddress = node.getAttribute(ATTR_LOCAL_BIND_ADDRESS).getString();
		isRegisteredAsForegnDevice = node.getAttribute(ATTR_REGISTER_AS_FOREIGN_DEVICE).getBool();
		bbmdIpList = node.getAttribute(ATTR_BBMD_IP_WITH_NETWORK_NUMBER).getString();
		if (null != bbmdIpList && isRegisteredAsForegnDevice) {
			this.parseBroadcastManagementDevice(bbmdIpList);
		}
	}

	Network createNetwork(int localNetworkNumber) {
		IpNetworkBuilder builder = new IpNetworkBuilder();
		Network network = builder.broadcastIp(broadcastIp).port(port).localBindAddress(localBindAddress)
				.localNetworkNumber(localNetworkNumber).build();
		return network;
	}

	private void parseBroadcastManagementDevice(String bbmdIpList) {
		String bbmdIp = null;
		int bbmdPort = IpNetwork.DEFAULT_PORT;
		int networkNumber = 0;
		for (String entry : bbmdIpList.split(",")) {
			entry = entry.trim();
			if (!entry.isEmpty()) {
				Pattern p = Pattern.compile("^\\s*(.*?):(\\d+):(\\d+)$");
                Matcher m = p.matcher(entry);
                if (m.matches()) {
                    bbmdIp = m.group(1);
                    bbmdPort = Integer.parseInt(m.group(2));
                    networkNumber = Integer.parseInt(m.group(3));
                    if (!bbmdIp.isEmpty()) {
                        bbmdIpToPort.put(bbmdIp, bbmdPort);
                        OctetString os = IpNetworkUtils.toOctetString(bbmdIp, bbmdPort);
                        networkRouters.put(networkNumber, os);
                    }
                }
            }
        }
	}

	@Override
	void addTransportParameters(Action act) {
		act.addParameter(new Parameter(ATTR_BROADCAST_IP, ValueType.STRING, node.getAttribute("broadcast ip")));
		act.addParameter(new Parameter(ATTR_PORT, ValueType.NUMBER, node.getAttribute("port")));
		act.addParameter(
				new Parameter(ATTR_LOCAL_BIND_ADDRESS, ValueType.STRING, node.getAttribute("local bind address")));
		act.addParameter(new Parameter(ATTR_REGISTER_AS_FOREIGN_DEVICE, ValueType.BOOL,
				node.getAttribute(ATTR_REGISTER_AS_FOREIGN_DEVICE)));
		act.addParameter(new Parameter(ATTR_BBMD_IP_WITH_NETWORK_NUMBER, ValueType.STRING,
				node.getAttribute(ATTR_BBMD_IP_WITH_NETWORK_NUMBER)));
	}

	@Override
	void setTransportAtrributions(ActionResult event) {
		String bip = event.getParameter(ATTR_BROADCAST_IP, ValueType.STRING).getString();
		int port = event.getParameter(ATTR_PORT, ValueType.NUMBER).getNumber().intValue();
		String lba = event.getParameter(ATTR_LOCAL_BIND_ADDRESS, ValueType.STRING).getString();
		boolean isfd = event.getParameter(ATTR_REGISTER_AS_FOREIGN_DEVICE, ValueType.BOOL).getBool();
		String bbmdips = event.getParameter(ATTR_BBMD_IP_WITH_NETWORK_NUMBER, ValueType.STRING).getString();

		node.setAttribute(ATTR_BROADCAST_IP, new Value(bip));
		node.setAttribute(ATTR_PORT, new Value(port));
		node.setAttribute(ATTR_LOCAL_BIND_ADDRESS, new Value(lba));
		node.setAttribute(ATTR_REGISTER_AS_FOREIGN_DEVICE, new Value(isfd));
		node.setAttribute(ATTR_BBMD_IP_WITH_NETWORK_NUMBER, new Value(bbmdips));
	}

	@Override
	void registerAsFeignDevice(Network network) {
		if (!isRegisteredAsForegnDevice)
			return;

		for (Map.Entry<String, Integer> entry : bbmdIpToPort.entrySet()) {
			String bbmdIp = entry.getKey();
			Integer bbmdPort = entry.getValue();
			try {
				((IpNetwork) network).registerAsForeignDevice(
						new InetSocketAddress(InetAddress.getByName(bbmdIp), bbmdPort), DEFAULT_TIME_TO_LIVE);
			} catch (UnknownHostException e) {
				LOGGER.debug("", e);
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}

		}

	}

	@Override
	String getDefaultMac() {
		InetAddress ip = null;
		try {
			ip = InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return ip.getHostAddress() + ":" + IpNetwork.DEFAULT_PORT;
	}

	@Override
	BacnetConn getBacnetConnection(BacnetLink link2, Node newnode) {
		BacnetConn bc = new BacnetIpConnection(link, newnode);

		return bc;
	}
}
