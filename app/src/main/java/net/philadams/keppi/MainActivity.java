package net.philadams.keppi;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.UUID;

public class MainActivity extends Activity implements BluetoothAdapter.LeScanCallback {

  public static final String TAG = MainActivity.class.getSimpleName();

  private final int REQUEST_ENABLE_BT = 1;

  // State machine
  final private static int STATE_BLUETOOTH_OFF = 1;
  final private static int STATE_DISCONNECTED = 2;
  final private static int STATE_CONNECTING = 3;
  final private static int STATE_CONNECTED = 4;

  private int state;

  private boolean scanStarted;
  private boolean scanning;

  private BluetoothAdapter bluetoothAdapter;
  private BluetoothDevice bluetoothDevice;

  private RFduinoService rfduinoService;

  private TextView scanStatusText;
  private Button scanButton;
  private TextView deviceInfoText;
  private TextView connectionStatusText;
  private Button connectButton;
  private Button clearButton;
  private LinearLayout dataLayout;

  ////////////////////////
  // BroadcastReceivers //
  ////////////////////////

  private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
      if (state == BluetoothAdapter.STATE_ON) {
        upgradeState(STATE_DISCONNECTED);
      } else if (state == BluetoothAdapter.STATE_OFF) {
        downgradeState(STATE_BLUETOOTH_OFF);
      }
    }
  };

  private final BroadcastReceiver scanModeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      scanning = (bluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_NONE);
      scanStarted &= scanning;
      updateUi();
    }
  };

  private final BroadcastReceiver rfduinoReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      final String action = intent.getAction();
      if (RFduinoService.ACTION_CONNECTED.equals(action)) {
        upgradeState(STATE_CONNECTED);
      } else if (RFduinoService.ACTION_DISCONNECTED.equals(action)) {
        downgradeState(STATE_DISCONNECTED);
      } else if (RFduinoService.ACTION_DATA_AVAILABLE.equals(action)) {
        addData(intent.getByteArrayExtra(RFduinoService.EXTRA_DATA));
      }
    }
  };

  //////////////
  // Services //
  //////////////

  private final ServiceConnection rfduinoServiceConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      rfduinoService = ((RFduinoService.LocalBinder) service).getService();
      if (rfduinoService.initialize()) {
        if (rfduinoService.connect(bluetoothDevice.getAddress())) {
          upgradeState(STATE_CONNECTING);
        }
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      rfduinoService = null;
      downgradeState(STATE_DISCONNECTED);
    }
  };

  ////////////////////////////
  // Activity state changes //
  ////////////////////////////

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // ensure bluetooth on (with user permission)
    ensureBluetoothEnabled();

    // Find Device
    scanStatusText = (TextView) findViewById(R.id.scanStatus);

    scanButton = (Button) findViewById(R.id.scan);
    scanButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        scanStarted = true;
        bluetoothAdapter.startLeScan(new UUID[] { RFduinoService.UUID_SERVICE }, MainActivity.this);
      }
    });

    // Device Info
    deviceInfoText = (TextView) findViewById(R.id.deviceInfo);

    // Connect Device
    connectionStatusText = (TextView) findViewById(R.id.connectionStatus);

    connectButton = (Button) findViewById(R.id.connect);
    connectButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        v.setEnabled(false);
        connectionStatusText.setText("Connecting...");
        Intent rfduinoIntent = new Intent(MainActivity.this, RFduinoService.class);
        bindService(rfduinoIntent, rfduinoServiceConnection, BIND_AUTO_CREATE);
      }
    });

    // Receive
    clearButton = (Button) findViewById(R.id.clearData);
    clearButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        dataLayout.removeAllViews();
      }
    });

    dataLayout = (LinearLayout) findViewById(R.id.dataLayout);
  }

  @Override
  protected void onStart() {
    super.onStart();

    registerReceiver(scanModeReceiver, new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED));
    registerReceiver(bluetoothStateReceiver,
        new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    registerReceiver(rfduinoReceiver, RFduinoService.getIntentFilter());

    updateState(bluetoothAdapter.isEnabled() ? STATE_DISCONNECTED : STATE_BLUETOOTH_OFF);
  }

  @Override
  protected void onStop() {
    super.onStop();

    bluetoothAdapter.stopLeScan(this);

    unregisterReceiver(scanModeReceiver);
    unregisterReceiver(bluetoothStateReceiver);
    unregisterReceiver(rfduinoReceiver);
  }

  ////////////////////
  // Helper methods //
  ////////////////////

  private void ensureBluetoothEnabled() {
    final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    bluetoothAdapter = bluetoothManager.getAdapter();
    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
      Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
      startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }
  }

  private void upgradeState(int newState) {
    if (newState > state) {
      updateState(newState);
    }
  }

  private void downgradeState(int newState) {
    if (newState < state) {
      updateState(newState);
    }
  }

  private void updateState(int newState) {
    state = newState;
    updateUi();
  }

  private void updateUi() {
    // Enable Bluetooth
    boolean on = state > STATE_BLUETOOTH_OFF;
    scanButton.setEnabled(on);

    // Scan
    if (scanStarted && scanning) {
      scanStatusText.setText("Scanning...");
      scanButton.setText("Stop Scan");
      scanButton.setEnabled(true);
    } else if (scanStarted) {
      scanStatusText.setText("Scan started...");
      scanButton.setEnabled(false);
    } else {
      scanStatusText.setText("");
      scanButton.setText("Scan");
      scanButton.setEnabled(true);
    }

    // Connect
    boolean connected = false;
    String connectionText = "Disconnected";
    if (state == STATE_CONNECTING) {
      connectionText = "Connecting...";
    } else if (state == STATE_CONNECTED) {
      connected = true;
      connectionText = "Connected";
    }
    connectionStatusText.setText(connectionText);
    connectButton.setEnabled(bluetoothDevice != null && state == STATE_DISCONNECTED);
  }

  private void addData(byte[] data) {
    View view = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, dataLayout, false);

    TextView text1 = (TextView) view.findViewById(android.R.id.text1);
    text1.setText(HexAsciiHelper.bytesToHex(data));

    String ascii = HexAsciiHelper.bytesToAsciiMaybe(data);
    if (ascii != null) {
      TextView text2 = (TextView) view.findViewById(android.R.id.text2);
      text2.setText(ascii);
    }

    dataLayout.addView(view, LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT);
  }

  @Override
  public void onLeScan(BluetoothDevice device, final int rssi, final byte[] scanRecord) {
    bluetoothAdapter.stopLeScan(this);
    bluetoothDevice = device;

    MainActivity.this.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        deviceInfoText.setText(
            BluetoothHelper.getDeviceInfoText(bluetoothDevice, rssi, scanRecord));
        updateUi();
      }
    });
  }
}

