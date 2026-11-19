package mcport.rt.patch;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Java Locale compatibility for methods not provided by TeaVM's class library. */
public final class LocalePatches {
    private LocalePatches() {
    }

    /**
     * Locale.getExtensionKeys() returns the singleton keys from a BCP-47 extension
     * sequence. TeaVM 0.16 does not expose this method; recover the keys from the
     * standard Locale.toString() extension suffix (for example, "#u-ca-japanese").
     */
    public static Set<Character> getExtensionKeys(Locale locale) {
        String value = locale.toString();
        int marker = value.lastIndexOf("#");
        if (marker < 0 || marker + 1 >= value.length()) {
            return Collections.emptySet();
        }

        String[] subtags = value.substring(marker + 1).replace('_', '-').split("-");
        Set<Character> keys = new LinkedHashSet<>();
        for (String subtag : subtags) {
            if (subtag.length() == 1) {
                char key = Character.toLowerCase(subtag.charAt(0));
                if (Character.digit(key, 36) >= 0) {
                    keys.add(key);
                }
            }
        }
        return keys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(keys);
    }

    /**
     * Locale.getISO3Country() uses ISO 3166 data that TeaVM 0.16 does not ship.
     * Minecraft only compares this value against the regional-compliancy keys,
     * so fall back to the alpha-2 code for countries outside the table.
     */
    public static String getISO3Country(Locale locale) {
        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        if (country.isEmpty()) {
            return "";
        }
        String iso3 = ISO3_COUNTRIES.get(country);
        return iso3 != null ? iso3 : country;
    }

    private static final Map<String, String> ISO3_COUNTRIES = iso3Countries();

    private static Map<String, String> iso3Countries() {
        String data =
            "AD=AND,AE=ARE,AF=AFG,AG=ATG,AI=AIA,AL=ALB,AM=ARM,AO=AGO,AQ=ATA,AR=ARG" +
            "AS=ASM,AT=AUT,AU=AUS,AW=ABW,AX=ALA,AZ=AZE,BA=BIH,BB=BRB,BD=BGD,BE=BEL" +
            "BF=BFA,BG=BGR,BH=BHR,BI=BDI,BJ=BEN,BL=BLM,BM=BMU,BN=BRN,BO=BOL,BQ=BES" +
            "BR=BRA,BS=BHS,BT=BTN,BV=BVT,BW=BWA,BY=BLR,BZ=BLZ,CA=CAN,CC=CCK,CD=COD" +
            "CF=CAF,CG=COG,CH=CHE,CI=CIV,CK=COK,CL=CHL,CM=CMR,CN=CHN,CO=COL,CR=CRI" +
            "CU=CUB,CV=CPV,CW=CUW,CX=CXR,CY=CYP,CZ=CZE,DE=DEU,DJ=DJI,DK=DNK,DM=DMA" +
            "DO=DOM,DZ=DZA,EC=ECU,EE=EST,EG=EGY,EH=ESH,ER=ERI,ES=ESP,ET=ETH,FI=FIN" +
            "FJ=FJI,FK=FLK,FM=FSM,FO=FRO,FR=FRA,GA=GAB,GB=GBR,GD=GRD,GE=GEO,GF=GUF" +
            "GG=GGY,GH=GHA,GI=GIB,GL=GRL,GM=GMB,GN=GIN,GP=GLP,GQ=GNQ,GR=GRC,GS=SGS" +
            "GT=GTM,GU=GUM,GW=GNB,GY=GUY,HK=HKG,HM=HMD,HN=HND,HR=HRV,HT=HTI,HU=HUN" +
            "ID=IDN,IE=IRL,IL=ISR,IM=IMN,IN=IND,IO=IOT,IQ=IRQ,IR=IRN,IS=ISL,IT=ITA" +
            "JE=JEY,JM=JAM,JO=JOR,JP=JPN,KE=KEN,KG=KGZ,KH=KHM,KI=KIR,KM=COM,KN=KNA" +
            "KP=PRK,KR=KOR,KW=KWT,KY=CYM,KZ=KAZ,LA=LAO,LB=LBN,LC=LCA,LI=LIE,LK=LKA" +
            "LR=LBR,LS=LSO,LT=LTU,LU=LUX,LV=LVA,LY=LBY,MA=MAR,MC=MCO,MD=MDA,ME=MNE" +
            "MF=MAF,MG=MDG,MH=MHL,MK=MKD,ML=MLI,MM=MMR,MN=MNG,MO=MAC,MP=MNP,MQ=MTQ" +
            "MR=MRT,MS=MSR,MT=MLT,MU=MUS,MV=MDV,MW=MWI,MX=MEX,MY=MYS,MZ=MOZ,NA=NAM" +
            "NC=NCL,NE=NER,NF=NFK,NG=NGA,NI=NIC,NL=NLD,NO=NOR,NP=NPL,NR=NRU,NU=NIU" +
            "NZ=NZL,OM=OMN,PA=PAN,PE=PER,PF=PYF,PG=PNG,PH=PHL,PK=PAK,PL=POL,PM=SPM" +
            "PN=PCN,PR=PRI,PS=PSE,PT=PRT,PW=PLW,PY=PRY,QA=QAT,RE=REU,RO=ROU,RS=SRB" +
            "RU=RUS,RW=RWA,SA=SAU,SB=SLB,SC=SYC,SD=SDN,SE=SWE,SG=SGP,SH=SHN,SI=SVN" +
            "SJ=SJM,SK=SVK,SL=SLE,SM=SMR,SN=SEN,SO=SOM,SR=SUR,SS=SSD,ST=STP,SV=SLV" +
            "SX=SXM,SY=SYR,SZ=SWZ,TC=TCA,TD=TCD,TF=ATF,TG=TGO,TH=THA,TJ=TJK,TK=TKL" +
            "TL=TLS,TM=TKM,TN=TUN,TO=TON,TR=TUR,TT=TTO,TV=TUV,TW=TWN,TZ=TZA,UA=UKR" +
            "UG=UGA,UM=UMI,US=USA,UY=URY,UZ=UZB,VA=VAT,VC=VCT,VE=VEN,VG=VGB,VI=VIR" +
            "VN=VNM,VU=VUT,WF=WLF,WS=WSM,YE=YEM,YT=MYT,ZA=ZAF,ZM=ZMB,ZW=ZWE";
        Map<String, String> countries = new HashMap<>(512);
        for (String entry : data.split(",")) {
            int separator = entry.indexOf('=');
            countries.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return countries;
    }
}
