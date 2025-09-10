package domain.impl;
import common.interfaces.ILamportClock;
public class LamportClockImpl implements ILamportClock {

    private int time = 0;

    @Override
    public synchronized int tick() {
        return  ++time;
    }

    @Override
    public synchronized int onSend() {

        return ++time;
    }

    @Override
    public synchronized int onReceive(int t){
        if ( t < 0) {
            t = 0;
        }
        time = Math.max(time, t) + 1;
        return time;
    }

    @Override
    public synchronized int getTime() {
        return time;
    }
}
