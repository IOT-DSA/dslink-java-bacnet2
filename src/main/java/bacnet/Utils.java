package bacnet;

import com.serotonin.bacnet4j.base.BACnetUtils;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.primitive.OctetString;

/**
 * @author Samuel Grenier
 */
public class Utils {

    public static Address toAddress(int netNum, String mac) {
        mac = mac.trim();
        int colon = mac.indexOf(":");
        if (colon == -1) {
            OctetString os = new OctetString(BACnetUtils.dottedStringToBytes(mac));
            return new Address(netNum, os);
        } else {
            byte[] ip = BACnetUtils.dottedStringToBytes(mac.substring(0, colon));
            int port = Integer.parseInt(mac.substring(colon + 1));
            return IpNetworkUtils.toAddress(netNum, ip, port);
        }
    }
}
