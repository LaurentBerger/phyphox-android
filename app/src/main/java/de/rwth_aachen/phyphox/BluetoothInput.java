package de.rwth_aachen.phyphox;


import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static java.lang.Math.pow;


/**
 * The BluetoothInput class encapsulates an input to Bluetooth devices.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class BluetoothInput extends Bluetooth {

    /**
     * UUID of the Descriptor for Client Characteristic Configuration
     */
    protected static final UUID CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /**
     * Used mode ("poll", "notification" or "indication")
     */
    private String mode;

    /**
     * Sensor acquisiton period in nanoseconds (inverse rate), 0 corresponds to as fast as possible
     */
    private long period;

    /**
     * Start time of the measurement or last measurement before a break to allow timestamps relative to the beginning of a measurement
     */
    private long t0 = 0;

    /**
     * Data-buffers
     */
    private Vector<dataOutput> data = new Vector<>();

    private Lock dataLock;

    /**
     * Used to store data in mode "poll" before it will be retrieved all together
     */
    protected HashMap<Integer, Double> outputs;

    /**
     * Create a new BluetoothInput.
     *
     * @param deviceName      name of the device (can be null if deviceAddress is not null)
     * @param deviceAddress   address of the device (can be null if deviceName is not null)
     * @param uuidFilter      Optional filter to identify devices by an advertised service or characteristic
     * @param mode            mode that should be used (can be "poll", "notification" or "indication")
     * @param rate            rate in Hz (only for mode "poll")
     * @param buffers         list of dataOutputs to write the values
     * @param lock            lock to write data to the buffers
     * @param context         context
     * @param characteristics list of all characteristics the object should be able to operate on
     * @throws phyphoxFile.phyphoxFileException if the value for rate is invalid.
     */
    public BluetoothInput(String deviceName, String deviceAddress, String mode, UUID uuidFilter, double rate, Vector<dataOutput> buffers, Lock lock, Activity activity, Context context, Vector<CharacteristicData> characteristics)
            throws phyphoxFile.phyphoxFileException {

        super(deviceName, deviceAddress, uuidFilter, activity, context, characteristics);

        this.mode = mode.toLowerCase();

        if (mode.equals("poll") && rate < 0) {
            throw new phyphoxFile.phyphoxFileException(context.getResources().getString(R.string.bt_exception_rate));
        }

        this.dataLock = lock;

        if (rate <= 0)
            this.period = 0; // as fast as possible
        else
            this.period = (long) ((1 / rate) * 1e9); //period in ns

        this.data = buffers;
    }

    /**
     * Connect with the device and enable notifications if mode is "notification" or indications if it is "indication".
     * The call to setValue of the BluetoothGattDescriptor to enable notifications has a lock because it should not be possible to continue before it succeeds.
     *
     * The timeout of the lock is set to 3 seconds.
     *
     * @throws BluetoothException if there is an error on findDevice, openConnection, process CharacteristicData or enable notifications/indications.
     */
    @Override
    public void connect() throws BluetoothException {
        super.connect(); // connect device

        // enable descriptor for notification
        if (mode.equals("notification") || mode.equals("indication")) {
            for (BluetoothGattCharacteristic characteristic : mapping.keySet()) {
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
                if (descriptor == null) {
                    throw new BluetoothException(context.getResources().getString(R.string.bt_exception_notification) + " " + characteristic.getUuid().toString() + " " + context.getResources().getString(R.string.bt_exception_notification_enable), this);
                }
                if (mode.equals("notification"))
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                cdl = new CancellableLatch(1);
                add(new WriteDescriptorCommand(btGatt, descriptor));
                boolean result = false;
                try {
                    // it should not be possible to continue before the notifications are turned on
                    // timeout after 3 seconds if the device could not be connected
                    result = cdl.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                }
                if (!result) {
                    throw new BluetoothException(context.getResources().getString(R.string.bt_exception_notification_fail_enable) + " " + characteristic.getUuid().toString() + " " + context.getResources().getString(R.string.bt_exception_notification_fail), this);
                }
            }
        }
    }

    /**
     * Disable descriptor for notification/indication again and then close the connection.
     * The call to setValue of the BluetoothGattDescriptor to disable notifications has a lock because it should not be possible to continue before it succeeds,
     * but there will be no error message if disabling notifications fails.
     *
     * The timeout of the lock is set to 2 seconds.
     *
     */
    @Override
    public void closeConnection() {
        // disable descriptor for notification
        if (mode.equals("notification")) {
            for (BluetoothGattCharacteristic characteristic : mapping.keySet()) {
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
                if (descriptor == null) {
                    continue;
                }
                descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                cdl = new CancellableLatch(1);
                add(new WriteDescriptorCommand(btGatt, descriptor));
                boolean result = false;
                try {
                    // it should not be possible to continue before the notifications are turned on
                    // timeout after 2 seconds if the device could not be connected
                    result = cdl.await(2, TimeUnit.SECONDS); // short timeout to not let the user wait when closing an experiment
                } catch (InterruptedException e) {
                }
            }
        }
        super.closeConnection(); // close connection
    }


    /**
     * Start the data acquisition.
     *
     * @throws BluetoothException if a notification could not be set.
     */
    @Override
    public void start() throws BluetoothException {
        outputs = new HashMap<>();
        if (!isRunning) { // don't reset t0 if the experiment is already running and bluetooth just paused because connection errors
            this.t0 = 0; //Reset t0. This will be set by the first sensor event
        }

        switch (mode) {
            case "poll": {
                final Runnable readData = new Runnable() {
                    @Override
                    public void run() {
                        // read data from all characteristics
                        for (BluetoothGattCharacteristic c : mapping.keySet()) {
                            add(new ReadCommand(btGatt, c));
                        }
                        mainHandler.postDelayed(this, period / 1000000l); // poll data again after the period is over
                    }
                };
                mainHandler.post(readData);
                break;
            }
            case "notification":
            case "indication": {
                // turn on characteristic notification for each characteristic
                for (BluetoothGattCharacteristic c : mapping.keySet()) {
                    boolean result = btGatt.setCharacteristicNotification(c, true);
                    if (!result) {
                        throw new BluetoothException(context.getResources().getString(R.string.bt_exception_notification) + " " + c.getUuid().toString() + " " + context.getResources().getString(R.string.bt_exception_notification_enable), this);
                    }
                }
                break;
            }
        }
        super.start();
    }


    /**
     * Stop the data acquisition.
     */
    @Override
    public void stop() {
        super.stop();
        switch (mode) {
            case "poll": {
                if (mainHandler != null) {
                    mainHandler.removeCallbacksAndMessages(null);
                }
                break;
            }
            case "notification":
            case "indication": {
                for (BluetoothGattCharacteristic c : mapping.keySet()) {
                    boolean result = btGatt.setCharacteristicNotification(c, false);
                }
                break;
            }
        }
    }

    /**
     * Called when a Characteristic was read.
     * Save data from the characteristic and call retrieveData if data from all characteristics is saved or if data from this characteristic is already saved and not retrieved yet.
     * If data is null, NaN will be saved.
     *
     * @param data           data read from the characteristic
     * @param characteristic characteristic that was read
     */
    @Override
    protected void saveData(byte[] data, BluetoothGattCharacteristic characteristic) {
        if (outputs != null) {
            for (Characteristic c : mapping.get(characteristic)) {
                // call retrieveData if the data from this characteristic is already stored
                if (outputs.containsKey(c.index)) {
                    retrieveData();
                    return;
                }
                // convert data and add it to outputs if it was read successfully, else write NaN
                if (data != null) {
                    outputs.put(c.index, convertData(data, c.inputConversionFunction));
                } else {
                    outputs.put(c.index, Double.NaN);
                }
            }
            // call retrieveData if data from every characteristic is received
            if (outputs.size() == valuesSize) {
                retrieveData();
            }
        }
    }

    /**
     * Called when there was a notification that the value of a Characteristic has changed.
     * Write data to the buffer immediately.
     *
     * @param data           data read from the characteristic
     * @param characteristic characteristic that got the notification
     */
    @Override
    protected void retrieveData(byte[] data, BluetoothGattCharacteristic characteristic) {
        ArrayList<Characteristic> characteristics = mapping.get(characteristic);
        long t = System.nanoTime();
        // set t0 if it is not yet set
        if (t0 == 0) {
            t0 = t;
            // find the last time data was retrieved
            double max = 0;
            for (Integer i : saveTime.values()) {
                dataOutput dataOutput = this.data.get(i);
                if (dataOutput != null && dataOutput.getFilledSize() > 0 && dataOutput.getValue() > max) {
                    max = dataOutput.getValue();
                }
            }
            t0 -= max * 1e9;
        }
        double[] outputs = new double[characteristics.size()];
        for (Characteristic c : characteristics) {
            outputs[characteristics.indexOf(c)] = convertData(data, c.inputConversionFunction);
        }

        //Append the data to available buffers
        dataLock.lock();
        try {
            for (Characteristic c : characteristics) {
                this.data.get(c.index).append(outputs[characteristics.indexOf(c)]);
            }
            // append time to buffer if extra=time is set
            if (saveTime.containsKey(characteristic)) {
                this.data.get(saveTime.get(characteristic)).append((t - t0) / 1e9);
            }
        } finally {
            dataLock.unlock();
        }
    }


    /**
     * Write data from all Characteristics to the buffers (mode "poll").
     */
    private void retrieveData() {
        long t = System.nanoTime();

        // set t0 if it is not yet set
        if (t0 == 0) {
            t0 = t;
            for (Integer i : saveTime.values()) {
                dataOutput dataOutput = data.get(i);
                if (dataOutput != null && dataOutput.getFilledSize() > 0) {
                    t0 -= dataOutput.getValue() * 1e9;
                    break;
                }
            }
        }

        //Append the data to available buffers
        dataLock.lock();
        try {
            for (ArrayList<Characteristic> al : mapping.values()) {
                for (Characteristic c : al) {
                    data.get(c.index).append(outputs.get(c.index));
                }
            }
            // append time to buffers
            for (Integer i : saveTime.values()) {
                data.get(i).append((t - t0) / 1e9);
            }
        } finally {
            dataLock.unlock();
            outputs.clear(); // remove values from receivedData because it is retrieved now
        }
    }


    /**
     * Convert data using the specified conversion function.
     * Return NaN in case of an exception.
     *
     * @param data               data that should be converted
     * @param conversionFunction InputConversion instance to convert data (from ConversionsInput)
     * @return the converted value
     */
    private double convertData(byte[] data, ConversionsInput.InputConversion conversionFunction) {
        try {
            return conversionFunction.convert(data);
        } catch (Exception e) {
            return Double.NaN; // return NaN if value could not be converted
        }
    }

} // end of class BluetoothInput