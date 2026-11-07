package cn.qingri.app;

import android.app.*;
import android.content.*;
import android.security.keystore.*;
import android.text.InputType;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials stay in app-private storage, encrypted with a non-exportable device key. */
public final class AgentSettings {
    private static final String PREF="deepseek_private",ALIAS="qingri.deepseek.v1";
    public static final String DEFAULT_MODEL="deepseek-flash";
    private AgentSettings(){}
    public static String model(Context c){return c.getSharedPreferences(PREF,0).getString("model",DEFAULT_MODEL);}
    public static boolean configured(Context c){return !c.getSharedPreferences(PREF,0).getString("cipher","").isEmpty();}
    private static SecretKey deviceKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static synchronized String key(Context c){
        SharedPreferences p=c.getSharedPreferences(PREF,0);String data=p.getString("cipher","");if(data.isEmpty())return "";
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,deviceKey(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);}
        catch(Exception ex){throw new IllegalStateException("密钥无法读取，请在设置中重新填写");}
    }
    public static synchronized void save(Context c,String model,String key){
        if(model==null||!model.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))throw new IllegalArgumentException("模型名格式不正确");
        SharedPreferences.Editor edit=c.getSharedPreferences(PREF,0).edit().putString("model",model.trim());
        if(key!=null&&!key.trim().isEmpty()){
            String clean=key.trim();if(clean.length()>512||!clean.matches("[!-~]+"))throw new IllegalArgumentException("密钥包含空格或无效字符");
            try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,deviceKey());edit.putString("cipher",Base64.encodeToString(cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));}
            catch(Exception ex){throw new IllegalStateException("设备加密不可用，密钥未保存");}
        }
        if(!edit.commit())throw new IllegalStateException("配置保存失败，请重试");
    }
    public static synchronized void clear(Context c){if(!c.getSharedPreferences(PREF,0).edit().remove("cipher").remove("iv").commit())throw new IllegalStateException("密钥清除失败");}
    public static void show(Activity a,Runnable onSaved){
        LinearLayout box=Ui.column(a);Ui.pad(box,22);
        TextView note=Ui.text(a,"DeepSeek 官方接口\nhttps://api.deepseek.com\n\n密钥仅加密保存在本机，不随备份导出。调用会产生服务商费用。",13,Ui.MUTED);note.setLineSpacing(Ui.dp(a,4),1);box.addView(note);Ui.gap(box,16);
        box.addView(Ui.bold(a,"模型",13,Ui.INK));EditText model=new EditText(a);model.setSingleLine(true);model.setText(model(a));model.setContentDescription("DeepSeek模型");box.addView(model);
        Ui.gap(box,12);box.addView(Ui.bold(a,configured(a)?"密钥已保存 · 留空保留原密钥":"API 密钥 · 可以暂时留空",13,Ui.INK));
        EditText secret=new EditText(a);secret.setSingleLine(true);secret.setHint("在这里填写 API Key");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);secret.setContentDescription("DeepSeek密钥");box.addView(secret);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("DeepSeek 设置").setView(box).setNegativeButton("取消",null).setNeutralButton("清除密钥",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{save(a,model.getText().toString(),secret.getText().toString());secret.setText("");dialog.dismiss();if(onSaved!=null)onSaved.run();Ui.toast(a,"配置已保存；尚未验证连接");}catch(RuntimeException ex){Ui.toast(a,ex.getMessage());}});
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(w->{try{clear(a);secret.setText("");dialog.dismiss();if(onSaved!=null)onSaved.run();Ui.toast(a,"密钥已清除");}catch(RuntimeException ex){Ui.toast(a,ex.getMessage());}});
        });dialog.setOnDismissListener(v->secret.setText(""));dialog.show();
    }
}
