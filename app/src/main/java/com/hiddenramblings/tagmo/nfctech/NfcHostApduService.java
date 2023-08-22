package com.hiddenramblings.tagmo.nfctech;

import android.nfc.cardemulation.HostApduService;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public class NfcHostApduService extends HostApduService {
    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if(commandApdu.equals("")){

        }
        return new byte[0];
    }

    @Override
    public void onDeactivated(int reason) {

    }
}
