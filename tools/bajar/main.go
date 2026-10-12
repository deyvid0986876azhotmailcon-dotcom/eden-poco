// bajar downloads a file over HTTPS from adb shell on a phone, where there is no curl or wget.
// It resumes partial files, retries on errors and prints its progress.
//
//	SSL_CERT_DIR=/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts ./bajar <url> <file>
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Println("usage: bajar <url> <file>")
		os.Exit(2)
	}
	url, path := os.Args[1], os.Args[2]

	// Android has no /etc/resolv.conf, so the Go resolver would ask localhost: ask public servers instead.
	servers := []string{"8.8.8.8:53", "1.1.1.1:53"}
	next := 0
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			server := servers[next%len(servers)]
			next++
			d := net.Dialer{Timeout: 10 * time.Second}
			return d.DialContext(ctx, network, server)
		},
	}
	dialer := &net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second, Resolver: resolver}
	client := &http.Client{Transport: &http.Transport{
		DialContext:           dialer.DialContext,
		TLSHandshakeTimeout:   30 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
	}}

	for attempt := 1; attempt <= 60; attempt++ {
		err := fetch(client, url, path)
		if err == nil {
			fmt.Println("COMPLETO")
			return
		}
		fmt.Printf("attempt %d failed: %v\n", attempt, err)
		time.Sleep(5 * time.Second)
	}
	fmt.Println("FALLO")
	os.Exit(1)
}

// fetch downloads what is missing of path. It returns nil only when the file is complete.
func fetch(client *http.Client, url, path string) error {
	var have int64
	if info, err := os.Stat(path); err == nil {
		have = info.Size()
	}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "bajar/1.0")
	if have > 0 {
		req.Header.Set("Range", "bytes="+strconv.FormatInt(have, 10)+"-")
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	var total int64 = -1
	flags := os.O_CREATE | os.O_WRONLY
	switch resp.StatusCode {
	case http.StatusPartialContent:
		// Content-Range: bytes <first>-<last>/<total>
		if i := strings.LastIndex(resp.Header.Get("Content-Range"), "/"); i >= 0 {
			total, _ = strconv.ParseInt(resp.Header.Get("Content-Range")[i+1:], 10, 64)
		}
		flags |= os.O_APPEND
	case http.StatusOK:
		have = 0
		total = resp.ContentLength
		flags |= os.O_TRUNC
	case http.StatusRequestedRangeNotSatisfiable:
		return nil // nothing left after what we already have
	default:
		return errors.New("HTTP " + resp.Status)
	}

	file, err := os.OpenFile(path, flags, 0o644)
	if err != nil {
		return err
	}
	defer file.Close()

	buf := make([]byte, 1<<20)
	start, last, startHave := time.Now(), time.Now(), have
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := file.Write(buf[:n]); werr != nil {
				return werr
			}
			have += int64(n)
		}
		if time.Since(last) >= 15*time.Second {
			last = time.Now()
			speed := float64(have-startHave) / 1048576 / time.Since(start).Seconds()
			fmt.Printf("%d of %d MB | %.2f MB/s\n", have>>20, total>>20, speed)
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return rerr
		}
	}
	if total >= 0 && have != total {
		return fmt.Errorf("got %d of %d bytes", have, total)
	}
	fmt.Printf("%d bytes\n", have)
	return nil
}
