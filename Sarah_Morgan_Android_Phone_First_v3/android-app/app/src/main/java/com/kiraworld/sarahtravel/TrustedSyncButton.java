package com.kiraworld.sarahtravel;
import android.content.Context; import android.content.Intent; import android.util.AttributeSet; import android.widget.Button;
public final class TrustedSyncButton extends Button { public TrustedSyncButton(Context c){super(c);i();}public TrustedSyncButton(Context c,AttributeSet a){super(c,a);i();}public TrustedSyncButton(Context c,AttributeSet a,int s){super(c,a,s);i();}private void i(){setAllCaps(false);setText("🔄 Devices & photos");setOnClickListener(v->getContext().startActivity(new Intent(getContext(),TrustedSyncActivity.class)));}}
