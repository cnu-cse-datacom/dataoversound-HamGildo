package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.min;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone(){

        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();

    }

    public void PreRequest() {
        List<Integer> byte_stream;
        List<Double> packet = new ArrayList<>();
        int blocksize = findPowerSize((int)(long)Math.round(interval/2*mSampleRate));
        short[] buffer = new short[blocksize];
        boolean in_packet = false;

        while(true){
            int bufferedReadResult = mAudioRecord.read(buffer,0,blocksize);

            double[] d_buffer = new double[buffer.length];
            for(int i = 0; i < buffer.length; i++){ // short to double
                d_buffer[i] = (double)buffer[i];
            }

            double dom = findFrequency(d_buffer);
            Log.d("freq: ", Double.toString(dom));

            if(in_packet && match(dom, HANDSHAKE_END_HZ)){
                byte_stream = extract_packet(packet);
                String result = "";

                for(int i = 0; i < byte_stream.size(); i++){
                    result += (char)(int)byte_stream.get(i);
                }
                Log.d("Result : ", result);
                packet.clear();
                in_packet = false;
            }
            else if(in_packet){
                packet.add(dom);
            }
            else if(match(dom, HANDSHAKE_START_HZ)) {
                in_packet = true;
            }


        }

    }

    private List<Integer> extract_packet(List<Double> packet) {
        List<Double> sample = new ArrayList<>();
        List<Integer> bit_chunks = new ArrayList<>();
      //  List<Integer> bytearray = new ArrayList<>();

        for(int i = 0; i < packet.size(); i++){ //sample 에 옮기기
            sample.add(packet.get(i));
        }

        for(int i = 1; i < sample.size(); i++) { //시작주파수를 제외함
            int chunk = (int)(Math.round((sample.get(i) - START_HZ) / STEP_HZ));
            if(0 <= chunk && chunk < 16) {
                bit_chunks.add(chunk);
            }
        }

//        for(int i = 0; i < bit_chunks.size(); i = i+2){
//            int a = bit_chunks.get(i);
//            int b = 0;
//            if(i+1 < bit_chunks.size()) {
//                b = bit_chunks.get(i + 1);
//            }
//            int makebyte = (a * 16) + b;
//
//            bytearray.add(makebyte);
//
//        }

        return decode_bitchunks(BITS, bit_chunks);
    }

    private List<Integer> decode_bitchunks(int bits, List<Integer> bitchunks) {
        List<Integer> bytearray = new ArrayList<>();

        int next_read_chunk = 0;
        int next_read_bit = 0;

        int by = 0;
        int bits_left = 8;
        while (next_read_chunk < bitchunks.size()) {
            int can_fill = bits - next_read_bit;
            int to_fill = min(bits_left, can_fill);
            int offset = bits - next_read_bit - to_fill;
            by <<=to_fill;
            int shifted = bitchunks.get(next_read_chunk) & (((1 << to_fill) - 1) << offset);
            by |=shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;
            if (bits_left <= 0) {
                bytearray.add(by);
                by =0;
                bits_left = 8;
            }

            if (next_read_bit >= bits) {
                next_read_chunk += 1;
                next_read_bit -= bits;
            }
        }

        return bytearray;
    }

    private int findPowerSize(int topower) { //2의 제곱수 형태로
        int two = 2;
        while(true) {
            if(two >= topower)
                break;
            two *= 2;
        }
        return two;
    }

    private boolean match(double a, double b){
        return Math.abs(a - b) < 20;
    }

    private double findFrequency(double[] toTransform) { //dominant
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform,TransformType.FORWARD);
        Double[] freq = this.fftfreq(complx.length, 1);

        for(int i = 0; i < complx.length; i++) {
            realNum = complx[i].getReal();
            imgNum = complx[i].getImaginary();
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));
        }

        int peak_coeff = 0;
        for(int i = 0; i < mag.length; i++) { //제일 큰 주파수의 인덱스찾기
            if(mag[peak_coeff] < mag[i]) {
                peak_coeff = i;
            }
        }
        double peak_freq = freq[peak_coeff];
        Log.d("peak_freq :::", String.valueOf(peak_freq));

        return Math.abs(peak_freq * mSampleRate); // in Hz
    }

    private Double[] fftfreq(int len, int d) {
        Double [] f = new Double[len];
        int n = len;

        if(len % 2 == 0){ //len 이 짝수
            double num = 0.0;
            for(int i = 0; i < len; i++){
                f[i] = num;
                num++;
                if(f[i] == n/2 - 1) {
                    num = -n/2;
                }
                f[i] /= (len * d);
            }
        }
        else{// 홀수
            double num = 0.0;
            for(int i = 0; i < len; i++){
                f[i] = num;
                num++;
                if(f[i] == (n-1)/2) {
                    num = -(n-1)/2;
                }
                f[i] /= (len * d);
            }
        }

        return f;
    }




}
