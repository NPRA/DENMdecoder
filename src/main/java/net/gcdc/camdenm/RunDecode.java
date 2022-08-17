package net.gcdc.camdenm;

import net.gcdc.asn1.uper.UperEncoder;
import net.gcdc.camdenm.CoopIts.Denm1;
import net.gcdc.camdenm.CoopIts.Denm2;
import net.gcdc.camdenm.CoopIts.DenmInterface;

public class RunDecode {

    static String testmsg = "1200500A038100400380652040018000310A00B77D0000140000020337424DCC90C49D23B67970066424BC8000000023B504C00666FA3800C800000000000007D2000001012E5BF27181172DF93889179099916F5004266474DFC5959EDC071B0CC38FFFFFFE11DBBA10070800001804500125000206BF17C272BC23B67970066424BC5A3C8101018003008009707265A61774E81083000000000021FB89DB8400C8010E00030840818002404281060501FFFFFFFF800308408381060501FFFFFFFF80012481040301FFFC80012581050401FFFFFF80018981030201E080018A81030201C080018B8107060130C001FFF800018D80013581060501FFFFFFFF80013681060501FFFFFFFF8002027D810201018002027E81050401FFFFFF8002027F81050401FFFFFF808082EE59289EBF79EE88A040708332CA735151E33E3E523399E5E18B29FF6CB3BA4E818033B32E8B017F6574A20ACAD9C2DA62EB2F9EB38123FE9901CE95270E39F1B93A93A520CB4878DB6DD17902AE5E7C1755AADAB5E33B42DC2C4AD05623B574A778808094DC9E56214DCA7F01CA11B2FDEDAE623DBD5A8DFAA073FC24A20388751008E57508E88DAB05DFECA439BC62EB3ED24FCB6359A550A6A1143A1E806BDB67A2F7";
    //"1234567890";//

    public static long myTest(String hexMsg) throws IllegalArgumentException, IllegalAccessException, InstantiationException, AssertionError {
        byte[] encoded = UperEncoder.bytesFromHexString(hexMsg);
        return myTest(encoded);
    }

    public static long myTest(byte[] encoded) throws IllegalArgumentException, IllegalAccessException, InstantiationException, AssertionError {
        DenmInterface decoded = UperEncoder.decode(encoded, Denm2.class);
        System.out.println(decoded);
        return decoded.getHeader().stationID.value;
    }

    public static DenmInterface getDecoded(byte[] encoded) throws IllegalArgumentException, IllegalAccessException, InstantiationException, AssertionError {
        DenmInterface denm = UperEncoder.decode(encoded, Denm2.class);
        if(denm.getHeader().protocolVersion.value == 1)
            denm = UperEncoder.decode(encoded, Denm1.class);
        return denm;
    }

    public static void runSearch(String msg)
    {
        System.out.println("Searching..");
        int size = msg.length();
        for(int j = 0; j<size;j++)
        {
            for (int i = size-j; i >= 0 ; i--) {
                String str = msg.substring(i, size-j);
                try {
                    //System.out.println(str+" - "+i+","+j);
                    if(RunDecode.myTest(str) == 12345)//777777777)
                        System.out.println("index: "+i+","+j+" - "+size);
                        System.exit(0);
                } catch (Throwable e) {
                    //e.printStackTrace();
                }
            }
        }
    }

    public static void main(String[] args) {
        String str = "02 01 00 00 30 39 E3 00 00 18 1C 80 00 10 A7 12 26 42 04 29 C6 17 DA AD 86 76 D8 87 44 23 27 4F FF FF F9 6A DB BA 1F 00 00 01 43 80 C0 50 00 0F C0 00 00";//"07D2000001012E5BF27181172DF93889179099916F5004266474DFC5959EDC071B0CC38FFFFFFE11DBBA10070800001804";//RunDecode.testmsg.substring(134, 224);
        //str = str.substring(8,str.length());
        System.out.println(str);
        System.out.println(RunDecode.testmsg.substring(134, 224));
        runSearch(str);
        try {
            System.out.println(RunDecode.myTest(str));
        } catch (Throwable e) {
            //e.printStackTrace();
        }
    }
}
