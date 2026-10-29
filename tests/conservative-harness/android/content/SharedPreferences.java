package android.content;
import java.util.Map;
public interface SharedPreferences {
 Map<String,?> getAll(); boolean contains(String k); boolean getBoolean(String k,boolean d);
 int getInt(String k,int d); long getLong(String k,long d); float getFloat(String k,float d);
 String getString(String k,String d); java.util.Set<String> getStringSet(String k,java.util.Set<String> d); Editor edit();
 interface Editor { Editor putBoolean(String k,boolean v); Editor putInt(String k,int v);
 Editor putLong(String k,long v); Editor putFloat(String k,float v); Editor putString(String k,String v); Editor putStringSet(String k,java.util.Set<String> v);
 Editor remove(String k); void apply(); boolean commit(); }
}