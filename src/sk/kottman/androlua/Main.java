package sk.kottman.androlua;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.http.util.EncodingUtils;
import org.keplerproject.luajava.JavaFunction;
import org.keplerproject.luajava.LuaException;
import org.keplerproject.luajava.LuaObject;
import org.keplerproject.luajava.LuaState;
import org.keplerproject.luajava.LuaStateFactory;

import android.R.string;
import android.app.Activity;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.*;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class Main extends Activity implements OnClickListener,
		OnLongClickListener {
	private final static int LISTEN_PORT = 3333;

	Button execute;
	Button test;
	
	// public so we can play with these from Lua
	public EditText source;
	public TextView status;
	public LuaState L;
	
	final StringBuilder output = new StringBuilder();

	Handler handler;
	ServerThread serverThread;
	
	private static byte[] readAll(InputStream input) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
		byte[] buffer = new byte[4096];
		int n = 0;
		while (-1 != (n = input.read(buffer))) {
			output.write(buffer, 0, n);
		}
		return output.toByteArray();
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		execute = (Button) findViewById(R.id.executeBtn);
		execute.setOnClickListener(this);
		test = (Button)findViewById(R.id.test);
		test.setOnClickListener(this);

		source = (EditText) findViewById(R.id.source);
		source.setOnLongClickListener(this);
		source.setText("require 'import'\nprint(Math:sin(2.3))\n");

		status = (TextView) findViewById(R.id.statusText);
		status.setMovementMethod(ScrollingMovementMethod.getInstance());

		handler = new Handler();

		L = LuaStateFactory.newLuaState();
		L.openLibs();

		try {
			L.pushJavaObject(this);
			L.setGlobal("activity");

			JavaFunction print = new JavaFunction(L) {
				@Override
				public int execute() throws LuaException {
					for (int i = 2; i <= L.getTop(); i++) {
						int type = L.type(i);
						String stype = L.typeName(type);
						String val = null;
						if (stype.equals("userdata")) {
							Object obj = L.toJavaObject(i);
							if (obj != null)
								val = obj.toString();
						} else if (stype.equals("boolean")) {
							val = L.toBoolean(i) ? "true" : "false";
						} else {
							val = L.toString(i);
						}
						if (val == null)
							val = stype;						
						output.append(val);
						output.append("\t");
					}
					output.append("\n");					
					return 0;
				}
			};
			print.register("print");

			JavaFunction assetLoader = new JavaFunction(L) {
				@Override
				public int execute() throws LuaException {
					String name = L.toString(-1);

					AssetManager am = getAssets();
					try {
						InputStream is = am.open(name + ".lua");
						byte[] bytes = readAll(is);
						L.LloadBuffer(bytes, name);
						return 1;
					} catch (Exception e) {
						ByteArrayOutputStream os = new ByteArrayOutputStream();
						e.printStackTrace(new PrintStream(os));
						L.pushString("Cannot load module "+name+":\n"+os.toString());
						return 1;
					}
				}
			};
			
			L.getGlobal("package");            // package
			L.getField(-1, "loaders");         // package loaders
			int nLoaders = L.objLen(-1);       // package loaders
			
			L.pushJavaFunction(assetLoader);   // package loaders loader
			L.rawSetI(-2, nLoaders + 1);       // package loaders
			L.pop(1);                          // package
						
			L.getField(-1, "path");            // package path
			String customPath = getFilesDir() + "/?.lua";
			L.pushString(";" + customPath);    // package path custom
			L.concat(2);                       // package pathCustom
			L.setField(-2, "path");            // package
			L.pop(1);
		} catch (Exception e) {
			status.setText("Cannot override print");
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		serverThread = new ServerThread();
		serverThread.start();
	}

	@Override
	protected void onPause() {
		super.onPause();
		serverThread.stopped = true;
	}

	private class ServerThread extends Thread {
		public boolean stopped;

		@Override
		public void run() {
			stopped = false;
			try {
				ServerSocket server = new ServerSocket(LISTEN_PORT);
				show("Server started on port " + LISTEN_PORT);
				while (!stopped) {
					Socket client = server.accept();
					BufferedReader in = new BufferedReader(
							new InputStreamReader(client.getInputStream()));
					final PrintWriter out = new PrintWriter(client.getOutputStream());
					String line = null;
					while (!stopped && (line = in.readLine()) != null) {
						final String s = line.replace('\001', '\n');
						if (s.startsWith("--mod:")) {
							int i1 = s.indexOf(':'), i2 = s.indexOf('\n');
							String mod = s.substring(i1+1,i2); 
							String file = getFilesDir()+"/"+mod.replace('.', '/')+".lua";
							FileWriter fw = new FileWriter(file);
							fw.write(s);
							fw.close();	
							// package.loaded[mod] = nil
							L.getGlobal("package");
							L.getField(-1, "loaded");
							L.pushNil();
							L.setField(-2, mod);
							out.println("wrote " + file + "\n");
							out.flush();
						} else {
							handler.post(new Runnable() {
								public void run() {
									String res = safeEvalLua(s);
									res = res.replace('\n', '\001');
									out.println(res);
									out.flush();
								}
							});
						}
					}
				}
				server.close();
			} catch (Exception e) {
				show(e.toString());
			}
		}

		private void show(final String s) {
			handler.post(new Runnable() {
				public void run() {
					status.setText(s);
				}
			});
		}
	}	

	String safeEvalLua(String src) {
		String res = null;	
		try {
			res = evalLua(src);
		} catch(LuaException e) {
			res = e.getMessage()+"\n";
		}
		return res;		
	}
	
	String evalLua(String src) throws LuaException {
		L.setTop(0);
		int ok = L.LloadString(src);
		if (ok == 0) {
			L.getGlobal("debug");
			L.getField(-1, "traceback");
			L.remove(-2);
			L.insert(-2);
			ok = L.pcall(0, 0, -2);
			if (ok == 0) {				
				String res = output.toString();
				output.setLength(0);
				return res;
			}
		}
		throw new LuaException(errorReason(ok) + ": " + L.toString(-1));
		//return null;		
		
	}

	public void onClick(View view) {
		int id = view.getId();
		switch (id) {
		case R.id.executeBtn:
			String src = source.getText().toString();
			status.setText("");
			try {
				String res = evalLua(src);
				status.append(res);
				status.append("Finished succesfully");
			} catch(LuaException e) {			
				Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();			
			}
//			Log.d("TAG", "test");
//			String src = getFromAssets("greet.lua");
//			source.setText(src);
//			String tempret = null;
//			try {
//				tempret = runGreetingFromLua(src, "einverne");
//			} catch (LuaException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//			Log.d("TAG", tempret);
			break;
		case R.id.test:
//			AssetManager am = getAssets();
//			String st = null;
//			try {
//				InputStream inputStream = am.open("BaseLib.lua");
//				st = loadTextFile(inputStream);
//			} catch (IOException e) {
//				// TODO: handle exception
//			}
			source.setText(getFromAssets("RunAPI.lua"));
			
			String hanzi = "十";
			String output = "89/260/92/260/95/260/98/260/101/260/104/260/113/260/116/260/119/260/122/260/125/260/128/260/131/260/137/260/143/260/146/260/152/260/158/260/161/260/164/260/170/260/173/260/179/260/182/260/188/260/191/260/194/260/197/260/203/260/209/260/212/260/215/260/218/260/221/260/224/260/227/260/230/260/233/260/236/260/239/260/242/260/245/260/248/260/251/260/254/260/256/260/259/260/262/260/265/260/268/260/271/260/274/260/277/260/280/260/283/260/286/260/289/260/292/260/295/260/298/260/301/260/304/260/310/260/313/260/319/260/325/260/328/260/331/260/337/260/340/260/349/260/352/260/355/260/361/260/364/260/367/260/370/260/376/260/379/260/382/260/385/260/388/260/391/260/394/257/397/254/400/254/403/254/406/254/409/254/412/254/415/254/418/254/421/254/424/254/427/254/430/254/433/254/436/251/439/251/442/251/445/251/448/251/451/251/454/251/457/251/460/251/463/251/466/251/469/251/469/251/@";
			String allpoints = "51/216/0/420/216/0/@234/51/0/234/431/0/@";
			
			String r = "1";
			L.LloadString(getFromAssets("WriteZiInfo.lua"));
			L.call(0, 0);
			L.LloadString(getFromAssets("StandardZiInfo.lua"));
			L.call(0, 0);
			L.LloadString(getFromAssets("BaseLib.lua"));
			L.call(0, 0);

			L.LloadString(getFromAssets("RunAPI.lua"));
			//找到函数
			L.getField(LuaState.LUA_GLOBALSINDEX, "GetWriteInfoFromC");		
			L.pushString(output);
			
			L.getField(LuaState.LUA_GLOBALSINDEX, "GetStandardZiInfoFromC");
			L.pushString(allpoints);
			L.getField(LuaState.LUA_GLOBALSINDEX, "GetZiNameFromC");
			L.pushString(hanzi);
			L.getField(LuaState.LUA_GLOBALSINDEX, "GetStrokeLevelFromC");
			L.pushString(r);
			
			L.getField(LuaState.LUA_GLOBALSINDEX, "GetRulesFromC");
			L.pushString(getFromAssets("loose.lua"));
			
			L.pcall(5, 1, 0);
			
//			L.setField(LuaState.LUA_GLOBALSINDEX, "Pass2CStr");
//			LuaObject obj = L.getLuaObject("Pass2CStr");
			
			L.getGlobal("testString");
			Toast.makeText(this, L.toString(-1), Toast.LENGTH_LONG).show();
			status.setText(L.toString(-1));
			
			// return "11\r\n"
			
//			L.LdoString("function foo(n) return n*2 end");
//			L.getGlobal("foo");
//			L.pushNumber(5.0);
//			L.pcall(1, 1, 0);
//			double result = L.toNumber(-1);
//			String text = ""+result;
//			status.setText(text); 
			break;
		default:
			break;
		}
		

	}

	private String errorReason(int error) {
		switch (error) {
		case 4:
			return "Out of memory";
		case 3:
			return "Syntax error";
		case 2:
			return "Runtime error";
		case 1:
			return "Yield error";
		}
		return "Unknown error " + error;
	}

	public boolean onLongClick(View view) {
		source.setText("");
		return true;
	}
	
	
	//从assets 文件夹中获取文件并读取数据
	public String getFromAssets(String fileName) {
		String result = "";
		try {
			InputStream in = getResources().getAssets().open(fileName);
			// 获取文件的字节数
			int lenght = in.available();
			// 创建byte数组
			byte[] buffer = new byte[lenght];
			// 将文件中的数据读到byte数组中
			in.read(buffer);
			result = EncodingUtils.getString(buffer, "UTF-8");
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}
		
	public String loadTextFile(InputStream inputStream) throws IOException {
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		byte[] bytes = new byte[4096];
		int len = 0;
		while ((len = inputStream.read(bytes)) > 0)
			byteStream.write(bytes, 0, len);
		return new String(byteStream.toByteArray(), "UTF-8");
	}
	
	public String runGreetingFromLua(String src_code, String arg) throws LuaException{
		LuaState Lo = LuaStateFactory.newLuaState();
		Lo.openLibs();
		Lo.setTop(0);
		String ret = null;
		int ok = Lo.LloadString(src_code);
		if (ok == 0) {
			Lo.getGlobal("hello");
			Lo.pushString(arg);
			Lo.call(1,1);
			ret = Lo.toString(-1);
			return ret;
		}
		Lo.close();
		
		throw new LuaException(errorReason(ok) + ": " + Lo.toString(-1));
		
	}
	
}