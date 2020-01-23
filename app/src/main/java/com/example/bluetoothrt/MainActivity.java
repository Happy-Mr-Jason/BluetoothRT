package com.example.bluetoothrt;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

public class MainActivity extends AppCompatActivity {

    public static final int REQUEST_ENABLE_BT = 10;
    private BluetoothAdapter bluetoothAdapter;
    private int mPairedDeviceCount = 0;
    private Set<BluetoothDevice> mDevices;
    private BluetoothDevice mRemoteDevice;
    private BluetoothSocket mSocket = null;
    private OutputStream mOutputStream = null;
    private InputStream mInputStream = null;
    private Thread mWorkerThread = null;
    private String mStrDelimiter = "\n";
    private char mCharDelimiter = '\n';
    private byte[] readBuffer;
    int readBufferPosition;
    private ImageView ivBTimg;
    private TextView tvConnectedDevice;
    private EditText edtSend;
    private TextView tvReceived;
    private Button btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ivBTimg = (ImageView) findViewById(R.id.ivBTimg);
        tvConnectedDevice = (TextView) findViewById(R.id.tvConnectedDevice);
        edtSend = (EditText) findViewById(R.id.edtSend);
        tvReceived = (TextView) findViewById(R.id.tvReceived);
        btnSend = (Button) findViewById(R.id.btnSend);

        checkBluetooth();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendData(edtSend.getText().toString());
                edtSend.setText("");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_ENABLE_BT:
                if (resultCode == RESULT_OK) {
                    selectDevice();
                } else if (requestCode == RESULT_CANCELED) {
                    showToast("블루투스 연결을 취소하였습니다.");
                }
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            mWorkerThread.interrupt(); //Finish Thread
            mInputStream.close();
            mOutputStream.close();
            mSocket.close(); // Finish Socket
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //Check the Bluetooth Connection
    void checkBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter(); // bluetoothAdapter Device
        if (bluetoothAdapter == null) {
            showToast("블루투스를 지원하지 않습니다.");
        } else {
            //if
            // Bluetooth is not Enabled
            if (!bluetoothAdapter.isEnabled()) {
                Intent enableBTIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBTIntent, REQUEST_ENABLE_BT);
            } else {
                //if Bluetooth is Enabled
                selectDevice();
            }
        }
    }

    //Select a Bluetooth Device
    void selectDevice() {
        mDevices = bluetoothAdapter.getBondedDevices();
        mPairedDeviceCount = mDevices.size();
        if (mPairedDeviceCount == 0) {
            showToast("연결할 블루투스 장치가 하나도 없습니다.");
        } else {
            AlertDialog.Builder dlgBuilder = new AlertDialog.Builder(this);
            dlgBuilder.setTitle("블루투스 장치 선택");
            final ArrayList<String> listItems = new ArrayList<>();
            for (BluetoothDevice device : mDevices) {
                listItems.add(device.getName());
            }
            listItems.add("취소");
            final CharSequence items[] = listItems.toArray(new CharSequence[listItems.size()]);
            dlgBuilder.setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == mPairedDeviceCount) {
                        showToast("취소를 선택했습니다.");
                    } else {
//                        connectToSelectedDevice(items[which].toString());
                        connectToSelectedDevice(listItems.get(which).toString());
                    }
                }
            });
            dlgBuilder.setCancelable(false);
            dlgBuilder.show();
        }
    }

    //Connect Selected Device
    void connectToSelectedDevice(String selectedDeviceName) {
        mRemoteDevice = getDeviceFromBondedList(selectedDeviceName);
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
        String uuids = "";
        for (int i = 0; i < mRemoteDevice.getUuids().length; i++) {
            uuids += mRemoteDevice.getUuids()[i].getUuid() + "\n";
        }
        Log.i("Selected_Device_UUIDs", uuids);
        try {
            mSocket = mRemoteDevice.createInsecureRfcommSocketToServiceRecord(uuid);
            mSocket.connect(); // Try Connect Device
            mOutputStream = mSocket.getOutputStream();
            mInputStream = mSocket.getInputStream();
            beginListenForData();
            ivBTimg.setImageResource(R.drawable.bluetooth_icon);
            tvConnectedDevice.setText(mRemoteDevice.getName() + "에 연결중" + "\n");
        } catch (Exception e) {
            showToast("블루투스 연결 중 오류가 발생하였습니다.");
            ivBTimg.setImageResource(R.drawable.bluetooth_grayicon);
            tvConnectedDevice.setText("");
        }
    }

    //Get Paired Device
    BluetoothDevice getDeviceFromBondedList(String name) {
        BluetoothDevice selectedDevice = null;
        for (BluetoothDevice device : mDevices) {
            if (name.equals(device.getName())) {
                selectedDevice = device;
                break;
            }
        }
        return selectedDevice;
    }

    //Ready to Receive Data from Device
    void beginListenForData() {
        final Handler handler = new Handler();
        readBuffer = new byte[1024];
        readBufferPosition = 0;
        mWorkerThread = new Thread(new Runnable() {
            @RequiresApi(api = Build.VERSION_CODES.KITKAT)
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        int bytesAvailable = mInputStream.available();
                        if (bytesAvailable > 0) {
                            byte packetBytes[] = new byte[bytesAvailable];
                            mInputStream.read(packetBytes);
                            for (int i = 0; i < bytesAvailable; i++) {
                                byte pByte = packetBytes[i];
                                if (pByte == mCharDelimiter) {
                                    // if find mCharDelimiter, process handler and init buffer.
                                    byte encodeBytes[] = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodeBytes, 0, encodeBytes.length);
                                    // data is a line that ending with "\n" received from Bluetooth.
                                    final String data = new String(encodeBytes, UTF_8);  //"US_ASCII"
                                    readBufferPosition = 0;
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            //Received data processing ///////////////////////////////////////////////////////
                                            tvReceived.setText(data + mStrDelimiter);
                                        }
                                    });
                                } else {
                                    // if don't find mCharDelimiter, read a line By byte
                                    readBuffer[readBufferPosition++] = pByte;
                                }
                            }
                        }
                    } catch (Exception e) {
                        showToast("데이터 수신 중 오류가 발생하였습니다.");
                    }
                }
            }
        });
        mWorkerThread.start();
    }

    // Ready to Transfer Data to Device

    void sendData(String msg) {
        showToast("Send Command : " + msg);
        msg += mStrDelimiter;
        try {
            mOutputStream.write(msg.getBytes());
        } catch (Exception e) {
            showToast("데이터 전송 중 오류가 발생하였습니다.");
        }
    }

    void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
