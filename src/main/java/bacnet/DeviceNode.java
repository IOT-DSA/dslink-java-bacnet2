package bacnet;

import java.util.List;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;

import bacnet.BacnetConn.CovType;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestListener;
import com.serotonin.bacnet4j.util.RequestUtils;

public class DeviceNode extends DeviceFolder {
	
	RemoteDevice device;
	long interval;
	CovType covType;
	
	DeviceNode(BacnetConn conn, Node node, RemoteDevice d) {
		super(conn, node);
		this.device = d;
		this.root = this;
		
		this.interval = node.getAttribute("refresh interval").getNumber().longValue();
		this.covType = CovType.NONE;
		try {
			this.covType = CovType.valueOf(node.getAttribute("cov usage").getString());
		} catch (Exception e) {
		}
		
		makeEditAction();
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("MAC address", ValueType.STRING, node.getAttribute("MAC address")));
	    act.addParameter(new Parameter("refresh interval", ValueType.NUMBER, node.getAttribute("refresh interval")));
	    act.addParameter(new Parameter("cov usage", ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED"), node.getAttribute("cov usage")));
	    act.addParameter(new Parameter("cov lease time (minutes)", ValueType.NUMBER, node.getAttribute("cov lease time (minutes)")));
	    node.createChild("edit").setAction(act).build().setSerializable(false);
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			long interv = event.getParameter("refresh interval", ValueType.NUMBER).getNumber().longValue();
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife =event.getParameter("cov lease time (minutes)", ValueType.NUMBER).getNumber().intValue();
			String mac = event.getParameter("MAC address", ValueType.STRING).getString();
			List<RemoteDevice> devset = conn.getDevice(mac, interval, covtype, covlife);
			if (devset.isEmpty()) return;
			final RemoteDevice d = devset.get(0);
			LocalDevice ld = conn.localDevice;
	        if (d == null || ld == null)
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
	        covType = covtype;
	        device = d;
	        interval = interv;
	        node.setAttribute("MAC address", new Value(d.getAddress().getMacAddress().toIpPortString()));
	        node.setAttribute("refresh interval", new Value(interv));
	        node.setAttribute("cov usage", new Value(covtype.toString()));
	        node.setAttribute("cov lease time (minutes)", new Value(covlife));
	        
	        node.removeChild("edit");
	        makeEditAction();
		}
	}
	
}
