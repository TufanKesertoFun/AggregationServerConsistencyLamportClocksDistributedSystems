package common.interfaces;

public interface ILamportClock {
    int tick();
    int onSend();
    int onReceive(int t);
    int getTime();
}
