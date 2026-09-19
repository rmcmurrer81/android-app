package com.kiraworld.sarahtravel;
import android.content.Context;import android.content.Intent;import android.util.AttributeSet;import android.widget.Button;
public final class DemoModeButton extends Button {public DemoModeButton(Context c){super(c);i();}public DemoModeButton(Context c,AttributeSet a){super(c,a);i();}public DemoModeButton(Context c,AttributeSet a,int s){super(c,a,s);i();}private void i(){setAllCaps(false);setText("▶ Event demo");setOnClickListener(v->getContext().startActivity(new Intent(getContext(),HackathonDemoActivity.class)));}}
