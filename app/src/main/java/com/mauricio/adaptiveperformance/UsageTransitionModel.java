package com.mauricio.adaptiveperformance;

import java.util.*;

/** Bounded Markov transition counter; no network, no full usage history. */
final class UsageTransitionModel {
    static final int MAX_EDGES = 32;
    private final Map<String,Integer> edges = new LinkedHashMap<>();
    private String prior = "";

    static boolean valid(String pkg) {
        return pkg != null && pkg.length() >= 5 && pkg.length() <= 150
                && pkg.matches("[a-zA-Z0-9_.]+")
                && !pkg.equals("android") && !pkg.startsWith("com.android.systemui")
                && !pkg.equals("com.mauricio.adaptiveperformance");
    }

    void observe(String current) {
        if (!valid(current)) return;
        if (current.equals(prior)) return;
        if (valid(prior)) {
            String key=prior+">"+current;
            edges.put(key, Math.min(9999, edges.getOrDefault(key,0)+1));
            if (edges.size()>MAX_EDGES) {
                String least=null; int count=Integer.MAX_VALUE;
                for (Map.Entry<String,Integer> e:edges.entrySet()) {
                    if (e.getValue()<count) { least=e.getKey(); count=e.getValue(); }
                }
                if (least!=null) edges.remove(least);
            }
        }
        prior=current;
    }

    /** Only predict with enough observed transitions, otherwise return empty. */
    String predict(String from) {
        if (!valid(from)) return "";
        String prefix=from+">";
        int total=0, best=0; String winner="";
        for (Map.Entry<String,Integer> e:edges.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            int n=e.getValue();
            total+=n;
            if (n>best) {
                best=n;
                winner=e.getKey().substring(prefix.length());
            }
        }
        return total>=5 && best>=3 && best*100 >= total*55 ? winner : "";
    }
    int edgeCount(){return edges.size();}

    String serialize(){
        StringBuilder b=new StringBuilder();
        if (valid(prior)) b.append(prior);
        b.append('|');
        for (Map.Entry<String,Integer> e:edges.entrySet()) {
            // Never serialize a model larger than parse() can restore.
            // Truncate at a complete edge boundary, not in the middle of an entry.
            String next = e.getKey() + ':' + e.getValue() + ';';
            if (b.length() + next.length() > 6500) break;
            b.append(next);
        }
        return b.toString();
    }
    static UsageTransitionModel parse(String encoded) {
        UsageTransitionModel model=new UsageTransitionModel();
        if (encoded==null || encoded.length()>7000) return model;
        try {
            String[] sections=encoded.split("\\|",-1);
            if (sections.length!=2) return model;
            if (valid(sections[0])) model.prior=sections[0];
            for(String entry:sections[1].split(";")) {
                if (entry.isEmpty()) continue;
                int colon=entry.lastIndexOf(':');
                if (colon<=0) continue;
                String key=entry.substring(0,colon);
                String[] pair=key.split(">",-1);
                if(pair.length!=2 || !valid(pair[0]) || !valid(pair[1]) ||
                        model.edges.size()>=MAX_EDGES) continue;
                int n=Integer.parseInt(entry.substring(colon+1));
                if(n>0 && n<=9999) model.edges.put(key,n);
            }
        } catch (RuntimeException ignored) { return new UsageTransitionModel(); }
        return model;
    }
}
