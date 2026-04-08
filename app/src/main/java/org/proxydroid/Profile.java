/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 K's Maze <kafkasmaze@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.proxydroid;

import android.content.ContentValues;
import android.database.Cursor;
import android.provider.BaseColumns;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * @author KsMaze
 *
 */
public class Profile implements Serializable {

	public static final String TABLE_NAME = "profiles";

	public static final class Columns implements BaseColumns {
		public static final String PROFILE_NAME = "profileName";
		public static final String HOST = "host";
		public static final String PORT = "port";
		public static final String BYPASS_ADDRS = "bypassAddrs";
		public static final String PROXIED_APPS = "proxiedApps";
		public static final String USER = "user";
		public static final String PASSWORD = "password";
		public static final String IS_AUTH = "isAuth";
		public static final String IS_NTLM = "isNTLM";
		public static final String DOMAIN = "domain";
		public static final String PROXY_TYPE = "proxyType";
		public static final String CERTIFICATE = "certificate";
		public static final String IS_AUTO_CONNECT = "isAutoConnect";
		public static final String IS_AUTO_SET_PROXY = "isAutoSetProxy";
		public static final String IS_BYPASS_APPS = "isBypassApps";
		public static final String IS_PAC = "isPAC";
		public static final String IS_DNS_PROXY = "isDNSProxy";
		public static final String SSID = "ssid";
		public static final String EXCLUDED_SSID = "excludedSsid";

		public static final String[] ALL_COLUMNS = {
				_ID,
				PROFILE_NAME,
				HOST,
				PROXY_TYPE,
				PORT,
				BYPASS_ADDRS,
				USER,
				PASSWORD,
				CERTIFICATE,
				PROXIED_APPS,
				IS_AUTH,
				IS_NTLM,
				IS_AUTO_CONNECT,
				IS_AUTO_SET_PROXY,
				IS_BYPASS_APPS,
				IS_DNS_PROXY,
				IS_PAC,
				DOMAIN,
				SSID,
				EXCLUDED_SSID
		};

		private Columns() {
		}
	}

	private String name;
	private String host;
	private String proxyType;
	private int port;
	private String bypassAddrs;
	private String user;
	private String password;
	private String certificate;
	private String proxiedApps;
	private boolean isAutoConnect = false;
	private boolean isAutoSetProxy = true;
	private boolean isBypassApps = false;
	private boolean isAuth = false;
	private boolean isNTLM = false;
	private boolean isDNSProxy = false;
	private boolean isPAC = false;

	private String domain;
	private String ssid;
	private String excludedSsid;

	public Profile() {
		init();
	}

	public void init() {
		host = "";
		port = 3128;
		ssid = "";
		user = "";
		domain = "";
		password = "";
		certificate = "";
		isAuth = false;
		proxyType = "http";
		isAutoConnect = false;
		ssid = "";
		excludedSsid = "";
		isNTLM = false;
		bypassAddrs = "";
		proxiedApps = "";
		isDNSProxy = false;
		isPAC = false;
		isAutoSetProxy = true;
	}

	public static String validateAddr(String ia) {

		boolean valid1 = Pattern.matches(
				"[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}/[0-9]{1,2}",
				ia);
		boolean valid2 = Pattern.matches(
				"[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}", ia);

		if (valid1 || valid2) {

			return ia;

		} else {

			String addrString;

			try {
				InetAddress addr = InetAddress.getByName(ia);
				addrString = addr.getHostAddress();
			} catch (Exception ignore) {
				addrString = null;
			}

			if (addrString != null) {
				boolean valid3 = Pattern.matches(
						"[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}",
						addrString);
				if (!valid3)
					addrString = null;
			}

			return addrString;
		}
	}

	public static String[] decodeAddrs(String addrs) {
		String[] list = addrs.split("\\|");
		ArrayList<String> ret = new ArrayList<>();
		for (String addr : list) {
			String ta = validateAddr(addr);
			if (ta != null)
				ret.add(ta);
		}
		return ret.toArray(new String[ret.size()]);
	}

	public static String encodeAddrs(String[] addrs) {

		if (addrs.length == 0)
			return "";

		StringBuilder sb = new StringBuilder();
		for (String addr : addrs) {
			String ta = validateAddr(addr);
			if (ta != null)
				sb.append(ta + "|");
		}
		String ret = sb.substring(0, sb.length() - 1);
		return ret;
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the ssid
	 */
	public String getSsid() {
		return ssid;
	}

	/**
	 * @return the excluded ssid
	 */
	public String getExcludedSsid() {
		return excludedSsid;
	}

	/**
	 * @param ssid the ssid to set
	 */
	public void setSsid(String ssid) {
		this.ssid = ssid;
	}

	/**
	 * @param ssid the excluded ssid to set
	 */
	public void setExcludedSsid(String ssid) {
		this.excludedSsid = ssid;
	}

	/**
	 * @return the host
	 */
	public String getHost() {
		return host;
	}

	/**
	 * @param host the host to set
	 */
	public void setHost(String host) {
		this.host = host;
	}

	/**
	 * @return the proxyType
	 */
	public String getProxyType() {
		return proxyType;
	}

	/**
	 * @param proxyType the proxyType to set
	 */
	public void setProxyType(String proxyType) {
		this.proxyType = proxyType;
	}

	/**
	 * @return the port
	 */
	public int getPort() {
		return port;
	}

	/**
	 * @param port the port to set
	 */
	public void setPort(int port) {
		this.port = port;
	}

	/**
	 * @return the bypassAddrs
	 */
	public String getBypassAddrs() {
		return bypassAddrs;
	}

	/**
	 * @param bypassAddrs the bypassAddrs to set
	 */
	public void setBypassAddrs(String bypassAddrs) {
		this.bypassAddrs = bypassAddrs;
	}

	public String getProxiedApps() {
		return proxiedApps;
	}

	public void setProxiedApps(String proxiedApps) {
		this.proxiedApps = proxiedApps;
	}

	/**
	 * @return the user
	 */
	public String getUser() {
		return user;
	}

	/**
	 * @param user the user to set
	 */
	public void setUser(String user) {
		this.user = user;
	}

	/**
	 * @return the password
	 */
	public String getPassword() {
		return password;
	}

	/**
	 * @param password the password to set
	 */
	public void setPassword(String password) {
		this.password = password;
	}

	/**
	 * @return the certificate
	 */
	public String getCertificate() {
		return certificate;
	}

	/**
	 * @param certificate the certificate to set
	 */
	public void setCertificate(String certificate) {
		this.certificate = certificate;
	}

	/**
	 * @return the isAutoConnect
	 */
	public Boolean isAutoConnect() {
		return isAutoConnect;
	}

	/**
	 * @param isAutoConnect the isAutoConnect to set
	 */
	public void setAutoConnect(Boolean isAutoConnect) {
		this.isAutoConnect = isAutoConnect;
	}

	/**
	 * @return the isAutoSetProxy
	 */
	public Boolean isAutoSetProxy() {
		return isAutoSetProxy;
	}

	/**
	 * @param isAutoSetProxy the isAutoSetProxy to set
	 */
	public void setAutoSetProxy(Boolean isAutoSetProxy) {
		this.isAutoSetProxy = isAutoSetProxy;
	}

	/**
	 * @return the isBypassApps
	 */
	public Boolean isBypassApps() {
		return isBypassApps;
	}

	/**
	 * @param isBypassApps the isBypassApps to set
	 */
	public void setBypassApps(Boolean isBypassApps) {
		this.isBypassApps = isBypassApps;
	}

	/**
	 * @return the isAuth
	 */
	public Boolean isAuth() {
		return isAuth;
	}

	/**
	 * @param isAuth the isAuth to set
	 */
	public void setAuth(Boolean isAuth) {
		this.isAuth = isAuth;
	}

	/**
	 * @return the isNTLM
	 */
	public Boolean isNTLM() {
		return isNTLM;
	}

	/**
	 * @param isNTLM the isNTLM to set
	 */
	public void setNTLM(Boolean isNTLM) {
		this.isNTLM = isNTLM;
	}

	/**
	 * @return the domain
	 */
	public String getDomain() {
		return domain;
	}

	/**
	 * @param domain the domain to set
	 */
	public void setDomain(String domain) {
		this.domain = domain;
	}

	/**
	 * @return the isDNSProxy
	 */
	public boolean isDNSProxy() {
		return isDNSProxy;
	}

	/**
	 * @param isDNSProxy the isDNSProxy to set
	 */
	public void setDNSProxy(boolean isDNSProxy) {
		this.isDNSProxy = isDNSProxy;
	}

	/**
	 * @return the isPAC
	 */
	public boolean isPAC() {
		return isPAC;
	}

	/**
	 * @param isPAC the isPAC to set
	 */
	public void setPAC(boolean isPAC) {
		this.isPAC = isPAC;
	}

	public static Profile fromCursor(Cursor c) {
		Profile p = new Profile();
		p.name = c.getString(c.getColumnIndexOrThrow(Columns.PROFILE_NAME));
		p.host = c.getString(c.getColumnIndexOrThrow(Columns.HOST));
		p.proxyType = c.getString(c.getColumnIndexOrThrow(Columns.PROXY_TYPE));
		p.port = c.getInt(c.getColumnIndexOrThrow(Columns.PORT));
		p.bypassAddrs = c.getString(c.getColumnIndexOrThrow(Columns.BYPASS_ADDRS));
		p.user = c.getString(c.getColumnIndexOrThrow(Columns.USER));
		p.password = c.getString(c.getColumnIndexOrThrow(Columns.PASSWORD));
		p.certificate = c.getString(c.getColumnIndexOrThrow(Columns.CERTIFICATE));
		p.proxiedApps = c.getString(c.getColumnIndexOrThrow(Columns.PROXIED_APPS));
		p.isAutoConnect = c.getInt(c.getColumnIndexOrThrow(Columns.IS_AUTO_CONNECT)) != 0;
		p.isAutoSetProxy = c.getInt(c.getColumnIndexOrThrow(Columns.IS_AUTO_SET_PROXY)) != 0;
		p.isBypassApps = c.getInt(c.getColumnIndexOrThrow(Columns.IS_BYPASS_APPS)) != 0;
		p.isAuth = c.getInt(c.getColumnIndexOrThrow(Columns.IS_AUTH)) != 0;
		p.isNTLM = c.getInt(c.getColumnIndexOrThrow(Columns.IS_NTLM)) != 0;
		p.isDNSProxy = c.getInt(c.getColumnIndexOrThrow(Columns.IS_DNS_PROXY)) != 0;
		p.isPAC = c.getInt(c.getColumnIndexOrThrow(Columns.IS_PAC)) != 0;
		p.domain = c.getString(c.getColumnIndexOrThrow(Columns.DOMAIN));
		p.ssid = c.getString(c.getColumnIndexOrThrow(Columns.SSID));
		p.excludedSsid = c.getString(c.getColumnIndexOrThrow(Columns.EXCLUDED_SSID));
		return p;
	}

	public ContentValues toContentValues() {
		ContentValues cv = new ContentValues();
		cv.put(Columns.PROFILE_NAME, name != null ? name : "");
		cv.put(Columns.HOST, host != null ? host : "");
		cv.put(Columns.PROXY_TYPE, proxyType != null ? proxyType : "http");
		cv.put(Columns.PORT, port);
		cv.put(Columns.BYPASS_ADDRS, bypassAddrs != null ? bypassAddrs : "");
		cv.put(Columns.USER, user != null ? user : "");
		cv.put(Columns.PASSWORD, password != null ? password : "");
		cv.put(Columns.CERTIFICATE, certificate != null ? certificate : "");
		cv.put(Columns.PROXIED_APPS, proxiedApps != null ? proxiedApps : "");
		cv.put(Columns.IS_AUTH, isAuth ? 1 : 0);
		cv.put(Columns.IS_NTLM, isNTLM ? 1 : 0);
		cv.put(Columns.IS_AUTO_CONNECT, isAutoConnect ? 1 : 0);
		cv.put(Columns.IS_AUTO_SET_PROXY, isAutoSetProxy ? 1 : 0);
		cv.put(Columns.IS_BYPASS_APPS, isBypassApps ? 1 : 0);
		cv.put(Columns.IS_PAC, isPAC ? 1 : 0);
		cv.put(Columns.IS_DNS_PROXY, isDNSProxy ? 1 : 0);
		cv.put(Columns.DOMAIN, domain != null ? domain : "");
		cv.put(Columns.SSID, ssid != null ? ssid : "");
		cv.put(Columns.EXCLUDED_SSID, excludedSsid != null ? excludedSsid : "");
		return cv;
	}

}
