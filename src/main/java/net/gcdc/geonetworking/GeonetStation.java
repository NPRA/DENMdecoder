package net.gcdc.geonetworking;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import net.gcdc.camdenm.RunDecode;
import net.gcdc.camdenm.CoopIts.Denm;
import net.gcdc.geonetworking.BasicHeader.NextHeader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/* Java and ETSI both use Big Endian. */
public class GeonetStation {

    private StationConfig                         config;

    private final static Logger logger = LoggerFactory.getLogger(GeonetStation.class);

    public final static short GN_ETHER_TYPE = (short) 0x8947;

    
    public GeonetStation(StationConfig conf) {
        config = conf;
    }



    /** Interface to lower layer (Ethernet/ITS-G5/802.11p, Link Layer) */
    private Denm decodeGeoNetworking(byte[] payload) {
        logger.debug("GN Received payload of size {}", payload.length);
        // Do we want to clone payload before we start reading from it?
        ByteBuffer buffer = ByteBuffer.wrap(payload).asReadOnlyBuffer();  // I promise not to write.
        try {

            BasicHeader  basicHeader  = BasicHeader.getFrom(buffer);
            if (basicHeader.version() != config.getItsGnProtocolVersion()) {
                logger.warn("Unrecognized protocol version: {}", basicHeader.version());
                return null;
            }

            //remove secure header "preamble"
            // TODO: use slice or something, this is stupid
            for(int i=0; i<7;i++)
            {
                StringBuilder sb = new StringBuilder();
                for (byte b : new byte[]{buffer.get()}) {
                    sb.append(String.format("%02X ", b));
                }
                //System.out.println(sb.toString());
            }
            logger.info("next header: {}",NextHeader.fromValue(basicHeader.nextHeader().value()).name());
            
            CommonHeader commonHeader = CommonHeader.getFrom(buffer);

            logger.info("common header type: {}",commonHeader.typeAndSubtype().name());

            switch (commonHeader.typeAndSubtype()) {
                case SINGLE_HOP: {
                    logger.info("Ignoring SINGLE_HOP {}",
                        commonHeader.typeAndSubtype().toString());
                    break;
                }
                case MULTI_HOP: {
                    logger.info("Ignoring MULTI_HOP {}",
                        commonHeader.typeAndSubtype().toString());
                    break;
                }
                case GEOBROADCAST_CIRCLE:
                case GEOBROADCAST_ELLIPSE:
                case GEOBROADCAST_RECTANGLE:
                case GEOANYCAST_CIRCLE:
                case GEOANYCAST_ELLIPSE:
                case GEOANYCAST_RECTANGLE:
                {
                    short sequenceNumber = buffer.getShort();
                    buffer.getShort();  // Reserved 16-bit.
                    LongPositionVector senderLpv = LongPositionVector.getFrom(buffer);
                    Area area = Area.getFrom(buffer, Area.Type.fromCode(commonHeader.typeAndSubtype().subtype()));
                    buffer.getShort();  // Reserved 16-bit.
                    byte[] upperPayload = new byte[commonHeader.payloadLength()];
                    buffer.slice().get(upperPayload, 0, commonHeader.payloadLength());

                    Destination.Geobroadcast destination = Destination.geobroadcast(area)
                            .withMaxLifetimeSeconds(basicHeader.lifetime().asSeconds())
                            .withRemainingHopLimit(basicHeader.remainingHopLimit())
                            .withMaxHopLimit(commonHeader.maximumHopLimit());
                    GeonetData indication = new GeonetData(
                            commonHeader.nextHeader(),
                            destination,
                            Optional.of(commonHeader.trafficClass()),
                            Optional.of(senderLpv),
                            upperPayload
                            );
                    PacketId packetId = new PacketId(Instant.now(), sequenceNumber, indication.sender.get().address().get());
                    logger.info("PacketId: {}",packetId.hashCode());
                    

                    BtpPacket btpP = BtpPacket.fromGeonetData(indication);
                    
                    StringBuilder sb = new StringBuilder();
                    for (byte b : btpP.payload()) {
                        sb.append(String.format("%02X ", b));
                    }
                    //System.out.println(sb.toString());
                    
                    try {
                        //TODO: this should be returned as an object, not just printed out
                        return RunDecode.getDecoded(btpP.payload());
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InstantiationException e) {
                        e.printStackTrace();
                    } catch (AssertionError e) {
                        e.printStackTrace();
                    }
                    break;
                }
                case BEACON: {
                    logger.info("Ignoring BEACON {}",
                            commonHeader.typeAndSubtype().toString());
                    break;
                }
                case LOCATION_SERVICE_REQUEST:
                case LOCATION_SERVICE_REPLY:
                    // Do nothing.
                    // At the moment we don't maintain Location Table.
                    logger.info("Ignoring Location Service {}",
                            commonHeader.typeAndSubtype().toString());
                    break;
                case GEOUNICAST: {
                    logger.info("Ignoring GEOUNICAST {}",
                            commonHeader.typeAndSubtype().toString());
                    break;
                }
                case ANY:
                default:
                    // Ignore for now.
                    logger.info("Ignoring {}", commonHeader.typeAndSubtype().toString());
                    break;
            }
        } catch (BufferUnderflowException | IllegalArgumentException ex) {
            logger.warn("Can't parse the packet, ignoring.", ex);
        }
        return null;
    }

    private class PacketId {
        private final Instant timestamp;
        private final Short sequenceNumber;
        private final Address sender;

        public PacketId(Instant timestamp, short sequenceNumber, Address sender) {
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
            this.sender = sender;
        }

        private GeonetStation getOuterType() {
            return GeonetStation.this;
        }

        @Override public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType().hashCode();
            result = prime * result + ((sender == null) ? 0 : sender.hashCode());
            result = prime * result + ((sequenceNumber == null) ? 0 : sequenceNumber.hashCode());
            result = prime * result + ((timestamp == null) ? 0 : timestamp.hashCode());
            return result;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            PacketId other = (PacketId) obj;
            if (!getOuterType().equals(other.getOuterType())) return false;
            if (sender == null) {
                if (other.sender != null) return false;
            } else if (!sender.equals(other.sender)) return false;
            if (sequenceNumber == null) {
                if (other.sequenceNumber != null) return false;
            } else if (!sequenceNumber.equals(other.sequenceNumber)) return false;
            if (timestamp == null) {
                if (other.timestamp != null) return false;
            } else if (!timestamp.equals(other.timestamp)) return false;
            return true;
        }
    }


    public static byte[] bytesFromHexString(String s) {
        s = s.replace(" ", "");
        if ((s.length() % 2) != 0) {
            throw new IllegalArgumentException(
                    "Converting to bytes requires even number of characters, got " + s.length());
        }
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
    
    
    static String testmsg = 
    // IVIM (should not work) "12 00 10 01 03 81 00 40 03 80 82 02 09 20 50 03 80 01 e5 01 00 14 00 00 02 03 37 42 4d f5 b8 7b ed 23 b6 79 70 06 64 24 bc 80 00 00 00 00 00 00 00 07 d4 00 00 01 04 79 ad 01 b8 00 38 16 44 59 8e 58 32 bc d6 80 dc 80 02 00 71 77 f0 f2 41 b4 05 83 30 30 43 12 76 bf a0 2c 19 01 1a 1b 93 be 94 c6 53 cc 5d 17 c9 e6 29 f2 aa 11 ea 11 ee 8f e5 08 f5 0b c5 0b c5 0b c7 43 92 85 e2 86 83 a0 2c 18 80 82 18 93 bc 3d 01 60 d8 18 10 c4 9d eb e8 0b 06 a0 a0 86 24 ed 7f 40 58 34 04 04 31 27 6e 7a 02 c1 c8 48 a3 b9 3b 21 3b 21 3b 23 d0 e4 9d 90 9d b9 e8 0b 07 01 04 8a e8 ca 14 f3 b4 f3 b7 47 f2 79 da 7b 42 7b 42 7b 43 a1 c9 3d a1 3d f3 d0 16 0d c1 c5 1d c9 d7 29 d7 29 d7 3e 87 24 eb 94 ec df 40 58 b2 0c 04 31 27 70 fa 02 c5 88 58 21 89 3b 5f d0 16 2c 02 89 15 c9 da e9 e1 29 e1 2e 8f e4 f0 94 f2 74 f2 74 f2 77 43 92 79 3a 79 df a0 2c 98 86 82 18 93 be bd 01 64 c8 38 10 c4 9d f5 e8 0b 26 61 e6 86 e4 ee 15 34 b4 ef 57 45 f2 77 aa 7a ca 7a ca 7a cb a3 f9 3d 65 3d dd 3d dd 3d dd d0 e4 9e ee 9f 16 e8 0b 26 82 06 86 e4 ee 15 34 b4 ef 57 45 f2 77 aa 7a ca 7a ca 7a cb a3 f9 3d 65 3d dd 3d dd 3d dd d0 e4 9e ee 9f 16 e8 0b 26 c2 40 86 24 f1 df 40 59 37 13 34 37 27 87 29 a5 a7 ac ba 2f 93 d6 53 ef 54 23 d4 23 dd 1f ca 11 ea 15 aa 15 aa 15 ae 87 25 0a d5 0c 17 40 59 38 14 34 37 27 87 29 a5 a7 ac ba 2f 93 d6 53 ef 54 23 d4 23 dd 1f ca 11 ea 15 aa 15 aa 15 ae 87 25 0a d5 0c 17 40 59 35 11 04 31 27 8e fa 02 cd c0 e0 21 89 3c 63 d0 16 6d c6 c1 0c 49 e0 9e 80 b3 68 30 08 62 4e f0 f4 05 9b 31 70 43 14 65 0f a0 2c db 0d 02 18 93 cb 3d 01 66 d4 64 10 c4 9d b9 e8 0b 36 42 c0 86 24 ef ff 40 59 b1 15 04 31 46 50 f8 40 01 89 00 02 07 5f da e3 45 c2 81 01 01 80 03 00 80 09 70 72 65 a6 17 74 e8 10 83 00 00 00 00 00 22 09 60 86 84 00 c8 01 0e 00 03 08 40 81 80 02 40 42 81 06 05 01 ff ff ff ff 80 03 08 40 83 81 06 05 01 ff ff ff ff 80 01 24 81 04 03 01 ff fc 80 01 25 81 05 04 01 ff ff ff 80 01 89 81 03 02 01 e0 80 01 8a 81 03 02 01 c0 80 01 8b 81 07 06 01 30 c0 01 ff f8 00 01 8d 80 01 35 81 06 05 01 ff ff ff ff 80 01 36 81 06 05 01 ff ff ff ff 80 02 02 7d 81 02 01 01 80 02 02 7e 81 05 04 01 ff ff ff 80 02 02 7f 81 05 04 01 ff ff ff 80 80 82 ca b9 05 2b b0 3c c2 d3 9f c0 e0 93 d8 d3 f9 af e8 cc 9f e7 b3 70 5e 6d 34 23 77 8c b9 b2 b8 ea 81 80 1e 8b 2b 0d a8 80 5b e1 81 8e c0 8f eb 3a ba c0 1c 8a 3f f9 b7 a5 92 f8 78 ef d0 9b 31 67 37 0f 64 ef 7c 89 1f 3e 62 19 bf 06 17 80 0d b7 e6 0c cc 4d a1 34 06 a6 85 04 fd 35 0c e2 91 41 a3 53 80 80 ea df 56 51 aa 72 c6 94 9e 21 36 29 7e 86 19 2d 4a 3a 1e ba 45 07 07 55 ea d4 e5 f4 71 c5 df 0c 82 93 a7 f0 dc 05 79 57 d3 4c a5 34 83 1b 16 68 ac c6 e7 38 52 cf 89 3e 6b 65 cc 33 ed 15 91 8d ";
    //v.2 "12 00 50 0a 03 81 00 40 03 80 65 20 40 01 80 00 31 0a 00 e1 4a 00 00 14 00 00 02 03 37 42 4d f5 9e d9 0d 23 b6 79 70 06 64 24 bc 80 00 00 00 24 72 11 00 05 31 2d e0 00 c8 00 00 00 00 00 00 07 d2 00 00 02 01 2e 5b f2 71 81 17 2d f9 38 80 00 90 38 ec d8 01 04 19 b1 19 9c 45 a1 6f a0 07 07 af fe 0f ff ff fe 11 db ba 10 a8 c0 00 00 03 02 50 01 25 00 02 07 5f 76 bb 29 13 23 b6 79 70 06 64 24 bc 5a 3c 81 01 01 80 03 00 80 09 70 72 65 a6 17 74 e8 10 83 00 00 00 00 00 22 09 60 86 84 00 c8 01 0e 00 03 08 40 81 80 02 40 42 81 06 05 01 ff ff ff ff 80 03 08 40 83 81 06 05 01 ff ff ff ff 80 01 24 81 04 03 01 ff fc 80 01 25 81 05 04 01 ff ff ff 80 01 89 81 03 02 01 e0 80 01 8a 81 03 02 01 c0 80 01 8b 81 07 06 01 30 c0 01 ff f8 00 01 8d 80 01 35 81 06 05 01 ff ff ff ff 80 01 36 81 06 05 01 ff ff ff ff 80 02 02 7d 81 02 01 01 80 02 02 7e 81 05 04 01 ff ff ff 80 02 02 7f 81 05 04 01 ff ff ff 80 80 82 ca b9 05 2b b0 3c c2 d3 9f c0 e0 93 d8 d3 f9 af e8 cc 9f e7 b3 70 5e 6d 34 23 77 8c b9 b2 b8 ea 81 80 1e 8b 2b 0d a8 80 5b e1 81 8e c0 8f eb 3a ba c0 1c 8a 3f f9 b7 a5 92 f8 78 ef d0 9b 31 67 37 0f 64 ef 7c 89 1f 3e 62 19 bf 06 17 80 0d b7 e6 0c cc 4d a1 34 06 a6 85 04 fd 35 0c e2 91 41 a3 53 80 80 d0 00 6b 3b e1 c1 57 38 ed 26 5e b3 0e b1 c8 9c 8f 97 19 11 20 f8 e3 b6 5b 54 44 dd 70 e9 f6 1c 61 6e 21 fb 61 44 fe 5c f9 92 0f 83 ea f3 4c 82 62 a4 3b 8c 70 96 ce 1f 21 4d 19 f4 a0 67 55 f1 ";
    "12 00 50 0a 03 81 00 40 03 80 65 20 40 01 80 00 31 0a 00 58 6f 00 00 14 00 00 02 03 37 42 4d e6 89 50 57 23 b6 79 70 06 64 24 bc 80 00 00 00 24 72 11 00 05 31 2d e0 00 c8 00 00 00 00 00 00 07 d2 00 00 01 01 2e 5b f2 71 81 17 2d f9 38 80 00 90 38 ec d8 01 04 19 b1 19 9c 45 a1 6f a0 07 07 af fe 0f ff ff fe 11 db ba 10 a8 c0 00 00 06 04 50 01 25 00 02 07 24 8a a1 a9 49 23 b6 79 70 06 64 24 bc 5a 3c 81 01 01 80 03 00 80 09 70 72 65 a6 17 74 e8 10 83 00 00 00 00 00 21 fb 89 db 84 00 c8 01 0e 00 03 08 40 81 80 02 40 42 81 06 05 01 ff ff ff ff 80 03 08 40 83 81 06 05 01 ff ff ff ff 80 01 24 81 04 03 01 ff fc 80 01 25 81 05 04 01 ff ff ff 80 01 89 81 03 02 01 e0 80 01 8a 81 03 02 01 c0 80 01 8b 81 07 06 01 30 c0 01 ff f8 00 01 8d 80 01 35 81 06 05 01 ff ff ff ff 80 01 36 81 06 05 01 ff ff ff ff 80 02 02 7d 81 02 01 01 80 02 02 7e 81 05 04 01 ff ff ff 80 02 02 7f 81 05 04 01 ff ff ff 80 80 82 ee 59 28 9e bf 79 ee 88 a0 40 70 83 32 ca 73 51 51 e3 3e 3e 52 33 99 e5 e1 8b 29 ff 6c b3 ba 4e 81 80 33 b3 2e 8b 01 7f 65 74 a2 0a ca d9 c2 da 62 eb 2f 9e b3 81 23 fe 99 01 ce 95 27 0e 39 f1 b9 3a 93 a5 20 cb 48 78 db 6d d1 79 02 ae 5e 7c 17 55 aa da b5 e3 3b 42 dc 2c 4a d0 56 23 b5 74 a7 78 80 80 f8 2b 39 9d 56 9e 44 25 9a 8f 33 8d dd c1 b2 0a 9d 7c ad 4b 7c c3 1f 98 9a b5 80 15 d4 7e b9 34 46 78 1b 44 b0 c1 81 81 70 fe 3f 4e 9f 76 1c 43 31 c3 45 c3 01 ce 2f 57 23 6d 68 d8 30 b9 88 47";
    //"1200500A038100400380652040018000310A00B77D0000140000020337424DCC90C49D23B67970066424BC8000000023B504C00666FA3800C800000000000007D2000001012E5BF27181172DF93889179099916F5004266474DFC5959EDC071B0CC38FFFFFFE11DBBA10070800001804500125000206BF17C272BC23B67970066424BC5A3C8101018003008009707265A61774E81083000000000021FB89DB8400C8010E00030840818002404281060501FFFFFFFF800308408381060501FFFFFFFF80012481040301FFFC80012581050401FFFFFF80018981030201E080018A81030201C080018B8107060130C001FFF800018D80013581060501FFFFFFFF80013681060501FFFFFFFF8002027D810201018002027E81050401FFFFFF8002027F81050401FFFFFF808082EE59289EBF79EE88A040708332CA735151E33E3E523399E5E18B29FF6CB3BA4E818033B32E8B017F6574A20ACAD9C2DA62EB2F9EB38123FE9901CE95270E39F1B93A93A520CB4878DB6DD17902AE5E7C1755AADAB5E33B42DC2C4AD05623B574A778808094DC9E56214DCA7F01CA11B2FDEDAE623DBD5A8DFAA073FC24A20388751008E57508E88DAB05DFECA439BC62EB3ED24FCB6359A550A6A1143A1E806BDB67A2F7";
    

    public static void main(String[] args) {

        StationConfig conf = new StationConfig();
        conf.setItsGnProtocolVersion(1);
        GeonetStation station = new GeonetStation(conf);
        Denm decodedDenm = station.decodeGeoNetworking(GeonetStation.bytesFromHexString(GeonetStation.testmsg));
        System.out.println(decodedDenm);
    }

}
