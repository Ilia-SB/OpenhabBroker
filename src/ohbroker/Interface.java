package ohbroker; 
public class Interface {
	
	public static final String BEGINTRANSMISSION 	= "AA";
	public static final String ENDTRANSMISSION 		= "3B";

	public static final String SETTARGETTEMP 	= "01";
	public static final String SETPRIORITY		= "02";
	public static final String SETAUTO			= "03";
	public static final String SETONOFF			= "04";
	public static final String SETSENSOR		= "05";
	public static final String SETPORT			= "06";
	public static final String SETADJUST		= "07";
	public static final String SETENABLED		= "08";
	public static final String SETCONSUMPTION	= "09";
	
	public static final String GETTEMP				= "11";
	public static final String GETSTATE				= "12";
	public static final String GETCONSUMPTIONLIMIT 	= "13";
	public static final String GETHYSTERESIS		= "14";
	public static final String GETTOTALCONSUMPTION 	= "15";
	public static final String GETACTUALSTATE	 	= "16";
	
	public static final String REPORTTEMP				= "21";
	public static final String REPORTSTATE				= "22";
	public static final String REPORTCONSUMPTIONLIMIT	= "23";
	public static final String REPORTHYSTERESIS			= "24";
	public static final String REPORTTOTALCONSUMPTION 	= "25";
	public static final String REPORTACTUALSTATE		= "26";
	
	public static final String SETCONSUMPTIONLIMIT	= "31";
	public static final String SETHYSTERESIS		= "32";
	
	public static final String REPORTSETTARGETTEMP	= "41";
	public static final String REPORTSETPRIORITY	= "42";
	public static final String REPORTSETAUTO		= "43";
	public static final String REPORTSETONOFF		= "44";
	public static final String REPORTSETSENSOR		= "45";
	public static final String REPORTSETPORT		= "46";
	public static final String REPORTSETADJUST		= "47";
	public static final String REPORTSETENABLED		= "48";
	public static final String REPORTSETCONSUMPTION	= "49";
	
	public static final String EEPROMERROR		= "50";
	public static final String TEMPREADERROR	= "51";
	
	public static final String DEBUGINFO		= "60";
	
}
